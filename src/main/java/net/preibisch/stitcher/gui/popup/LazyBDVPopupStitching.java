/*-
 * #%L
 * Multiview stitching of large datasets.
 * %%
 * Copyright (C) 2016 - 2026 Big Stitcher developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.stitcher.gui.popup;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import bdv.BigDataViewer;
import bdv.SpimSource;
import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;
import bdv.VolatileSpimSource;
import bdv.cache.CacheControl;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.MinMaxGroup;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerOptions;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.apply.BigDataViewerTransformationWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.SelectedViewDescriptionListener;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.ScrollableBrightnessDialog;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVSourceNaming;
import net.preibisch.stitcher.gui.MaximumProjectorARGB;
import net.preibisch.stitcher.gui.overlay.LinkOverlay;
import util.BDVTools;

/**
 * BigStitcher's lazy / on-the-fly BDV popup.
 *
 * <p>It is a {@link BDVPopup} (so the inherited {@code bdvPopup()} accessor and
 * its ~60 call sites keep working unchanged), but instead of eagerly building one
 * source per view-setup like {@link BDVPopupStitching}, it opens BigDataViewer with
 * NO sources and then adds / removes sources as views are selected in the explorer.
 * This sidesteps the eager-init O(N) source construction and the O(N²) brightness
 * dialog listener cascade on heavily-split datasets.
 *
 * <p>The dynamic add/remove machinery is ported from MVR's
 * {@code net.preibisch.mvrecon.fiji.spimdata.explorer.popup.LazyBDVPopup}; on top of
 * that this class renders with the {@link MaximumProjectorARGB} max-projection and
 * attaches the stitching {@link LinkOverlay} so tile links stay visible. Per-channel
 * grouping/coloring done eagerly in {@link BDVPopupStitching} is intentionally not
 * re-applied here — lazily added sources use histogram-based brightness.
 */
public class LazyBDVPopupStitching extends BDVPopup
{
	private static final long serialVersionUID = 4196981337051609842L;

	/**
	 * If {@code true}, deselecting a view removes its source (lower memory); if
	 * {@code false}, the source is only hidden (faster re-select, list grows).
	 */
	public static boolean removeOnDeselect = true;

	private final LinkOverlay lo;

	// Per-instance state for the running lazy BDV.
	private final Map< Integer, SourceAndConverter< ? > > activeBySetupId = new HashMap<>();
	private final Map< Integer, ConverterSetup > setupBySetupId = new HashMap<>();
	private double[] sharedRange = null;
	private boolean firstTransformDone = false;
	private SelectedViewDescriptionListener< ? > registeredListener = null;

	public LazyBDVPopupStitching( final LinkOverlay lo )
	{
		super();
		this.lo = lo;
		setText( "Display in BigDataViewer (lazy on/off)" );
		// replace the eager BDVPopup action listener with the lazy toggle
		this.removeActionListener( this.getActionListeners()[ 0 ] );
		this.addActionListener( new MyActionListener() );
	}

	public class MyActionListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}
			new Thread( () -> {
				if ( bdv != null && !bdv.getViewerFrame().isVisible() )
				{
					bdv = null;
					resetState();
				}
				if ( bdv == null )
					openBDV( panel );
				else
					closeBDV();
			} ).start();
		}
	}

	/**
	 * Open BDV in lazy mode for {@code panel}'s data, if not already open. Public
	 * so the explorer auto-open path can call it directly (mirrors
	 * {@code LazyBDVPopup#openBDV}).
	 */
	public BigDataViewer openBDV( final ExplorerWindow< ? > panel )
	{
		if ( bdv != null && bdv.getViewerFrame().isVisible() )
			return bdv;
		if ( bdv != null )
		{
			bdv = null;
			resetState();
		}
		try
		{
			bdv = createLazyBDV( panel );
		}
		catch ( final Exception ex )
		{
			IOFunctions.println( "Could not run BigDataViewer (lazy): " + ex );
			ex.printStackTrace();
			bdv = null;
		}
		return bdv;
	}

	@Override
	public void closeBDV()
	{
		if ( bdvRunning() )
			BigDataViewerTransformationWindow.disposeViewerWindow( bdv );
		bdv = null;
		resetState();
	}

	@Override
	public void updateBDV()
	{
		if ( bdv == null )
			return;
		bdv.getViewer().requestRepaint();
		bdv.getViewer().getDisplay().repaint();
	}

	/**
	 * Adopt an already-running (lazy) BDV when the explorer transfers it across a
	 * panel rebuild (e.g. Stitching &lt;-&gt; Multiview mode switch). Re-attaches the
	 * link overlay + selection listener to this panel and syncs the current selection.
	 */
	@Override
	public void setBDV( final BigDataViewer existingBdv )
	{
		this.bdv = existingBdv;
		if ( bdv == null )
			return;

		BDVTools.setFusedModeSimple( bdv, panel.getSpimData() );

		if ( lo != null )
		{
			bdv.getViewer().removeTransformListener( lo );
			bdv.getViewer().addTransformListener( lo );
			bdv.getViewer().getDisplay().addOverlayRenderer( lo );
		}

		// the transferred BDV already carries sources from the previous panel;
		// re-register the listener on this panel and re-sync to the current selection.
		registerSelectionListener( panel );
		syncToCurrentSelection( panel );

		bdv.getViewer().requestRepaint();
	}

	private void resetState()
	{
		activeBySetupId.clear();
		setupBySetupId.clear();
		sharedRange = null;
		firstTransformDone = false;
		registeredListener = null;
	}

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private BigDataViewer createLazyBDV( final ExplorerWindow< ? > panel )
	{
		final long tStart = System.currentTimeMillis();
		final AbstractSpimData< ? > spimData = panel.getSpimData();
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		IOFunctions.println( "[LazyBDV-open] starting (view-setups="
				+ seq.getViewSetupsOrdered().size() + ", img-loader="
				+ seq.getImgLoader().getClass().getSimpleName()
				+ ", removeOnDeselect=" + removeOnDeselect + ")" );

		// Open BDV with NO sources; sources are added on selection. Use BigStitcher's
		// max-projection accumulate projector to match the eager BDVPopupStitching look.
		final ArrayList< ConverterSetup > emptyConverterSetups = new ArrayList<>();
		final ArrayList< SourceAndConverter< ? > > emptySources = new ArrayList<>();
		final int numTimepoints = seq.getTimePoints().size();
		final CacheControl cache = ( ( ViewerImgLoader ) seq.getImgLoader() ).getCacheControl();
		final ViewerOptions options = ViewerOptions.options().accumulateProjectorFactory( MaximumProjectorARGB.factory );
		final BigDataViewer newBdv = new BigDataViewer( emptyConverterSetups, emptySources, spimData,
				numTimepoints, cache, panel.xml().toString(),
				IOFunctions.getProgressWriter(), options );
		newBdv.getViewerFrame().setVisible( true );
		ScrollableBrightnessDialog.setAsBrightnessDialog( newBdv );

		// fused display so multiple active sources render simultaneously
		BDVTools.setFusedModeSimple( newBdv, spimData );

		// assign before the initial sync so syncSources sees the live instance
		this.bdv = newBdv;

		// stitching link overlay
		if ( lo != null )
		{
			newBdv.getViewer().addTransformListener( lo );
			newBdv.getViewer().getDisplay().addOverlayRenderer( lo );
		}

		IOFunctions.println( "[LazyBDV-open] empty BDV opened: " + ( System.currentTimeMillis() - tStart ) + "ms" );

		registerSelectionListener( panel );
		syncToCurrentSelection( panel );

		IOFunctions.println( "[LazyBDV-open] TOTAL: " + ( System.currentTimeMillis() - tStart ) + "ms" );

		return newBdv;
	}

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private void registerSelectionListener( final ExplorerWindow< ? > panel )
	{
		final FilteredAndGroupedExplorerPanel rawPanel = ( FilteredAndGroupedExplorerPanel ) panel;
		final AbstractSpimData< ? > spimData = panel.getSpimData();
		final SelectedViewDescriptionListener listener = new SelectedViewDescriptionListener()
		{
			@Override
			public void selectedViewDescriptions( final List viewDescriptions )
			{
				if ( bdv == null || !bdv.getViewerFrame().isVisible() )
					return;
				try
				{
					final List< List< BasicViewDescription< ? > > > vds =
							( List< List< BasicViewDescription< ? > > > ) viewDescriptions;
					syncSources( spimData, vds );
				}
				catch ( final Exception ex )
				{
					IOFunctions.println( "[LazyBDV] sync failed: " + ex );
					ex.printStackTrace();
				}
			}

			@Override public void updateContent( final AbstractSpimData data ) {}
			@Override public void save() {}
			@Override public void quit() {}
		};
		rawPanel.addListener( listener );
		registeredListener = listener;
	}

	private void syncToCurrentSelection( final ExplorerWindow< ? > panel )
	{
		final AbstractSpimData< ? > spimData = panel.getSpimData();
		final List< List< BasicViewDescription< ? > > > current = new ArrayList<>();
		for ( final List< BasicViewDescription< ? > > row : ( ( FilteredAndGroupedExplorerPanel< ? > ) panel ).selectedRows )
			current.add( row );
		try { syncSources( spimData, current ); }
		catch ( final Exception ex ) { ex.printStackTrace(); }
	}

	private void syncSources( final AbstractSpimData< ? > spimData,
			final List< List< BasicViewDescription< ? > > > selected )
	{
		if ( bdv == null || !bdv.getViewerFrame().isVisible() )
			return;

		final HashSet< Integer > wantedIds = new HashSet<>();
		for ( final List< BasicViewDescription< ? > > row : selected )
			for ( final BasicViewDescription< ? > vd : row )
				wantedIds.add( vd.getViewSetupId() );

		final ArrayList< Integer > toAdd = new ArrayList<>();
		for ( final Integer id : wantedIds )
			if ( !activeBySetupId.containsKey( id ) )
				toAdd.add( id );
		final ArrayList< Integer > toRemoveOrHide = new ArrayList<>();
		for ( final Integer id : activeBySetupId.keySet() )
			if ( !wantedIds.contains( id ) )
				toRemoveOrHide.add( id );

		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();
		final int sampleTimepoint = seq.getTimePoints().getTimePointsOrdered().get( 0 ).getId();

		// brightness range for new adds: prefer a currently-active setup, then the
		// carried-forward default, else sample a histogram from the first add.
		double[] addRange = null;
		if ( !setupBySetupId.isEmpty() )
		{
			final ConverterSetup any = setupBySetupId.values().iterator().next();
			if ( any != null )
				addRange = new double[]{ any.getDisplayRangeMin(), any.getDisplayRangeMax() };
		}
		if ( addRange == null && sharedRange != null )
			addRange = sharedRange;

		if ( !toRemoveOrHide.isEmpty() )
		{
			final ConverterSetup csR = setupBySetupId.get( toRemoveOrHide.get( 0 ) );
			if ( csR != null )
				sharedRange = new double[]{ csR.getDisplayRangeMin(), csR.getDisplayRangeMax() };
		}

		// ADD
		final ArrayList< SourceAndConverter< ? > > addList = new ArrayList<>();
		for ( final Integer id : toAdd )
		{
			final BasicViewSetup setup = seq.getViewSetups().get( id );
			if ( setup == null )
				continue;
			final SourceAndConverter< ? > soc = createSourceAndConverter( spimData, setup );
			final ConverterSetup cs = BigDataViewer.createConverterSetup( soc, id );

			if ( addRange == null )
			{
				sharedRange = sampleAndComputeRange( soc.getSpimSource(), sampleTimepoint );
				addRange = sharedRange;
			}
			if ( cs != null && addRange != null )
				cs.setDisplayRange( addRange[ 0 ], addRange[ 1 ] );

			if ( cs != null )
			{
				bdv.getViewerFrame().getConverterSetups().put( soc, cs );
				bdv.getSetupAssignments().addSetup( cs );

				final List< MinMaxGroup > groups = bdv.getSetupAssignments().getMinMaxGroups();
				if ( groups.size() > 1 )
					bdv.getSetupAssignments().moveSetupToGroup( cs, groups.get( 0 ) );
			}
			addList.add( soc );
			activeBySetupId.put( id, soc );
			setupBySetupId.put( id, cs );
		}
		if ( !addList.isEmpty() )
		{
			bdv.getViewer().state().addSources( addList );
			bdv.getViewer().state().setSourcesActive( addList, true );
		}

		// REMOVE or HIDE
		if ( removeOnDeselect )
		{
			final ArrayList< SourceAndConverter< ? > > removeList = new ArrayList<>();
			for ( final Integer id : toRemoveOrHide )
			{
				final SourceAndConverter< ? > soc = activeBySetupId.remove( id );
				setupBySetupId.remove( id );
				if ( soc != null )
					removeList.add( soc );
			}
			if ( !removeList.isEmpty() )
				bdv.getViewer().state().removeSources( removeList );
		}
		else
		{
			final ArrayList< SourceAndConverter< ? > > hideList = new ArrayList<>();
			for ( final Integer id : toRemoveOrHide )
			{
				final SourceAndConverter< ? > soc = activeBySetupId.get( id );
				if ( soc != null )
					hideList.add( soc );
			}
			if ( !hideList.isEmpty() )
				bdv.getViewer().state().setSourcesActive( hideList, false );
		}

		if ( !firstTransformDone && !activeBySetupId.isEmpty() )
		{
			firstTransformDone = true;
			BDVPopup.initTransform( bdv.getViewer() );
		}
	}

	private static < T extends NumericType< T >, V extends Volatile< T > & NumericType< V > > SourceAndConverter< T >
			createSourceAndConverter( final AbstractSpimData< ? > spimData, final BasicViewSetup setup )
	{
		final int setupId = setup.getId();
		final ViewerImgLoader imgLoader = ( ViewerImgLoader ) spimData.getSequenceDescription().getImgLoader();
		@SuppressWarnings( "unchecked" )
		final ViewerSetupImgLoader< T, V > setupImgLoader = ( ViewerSetupImgLoader< T, V > ) imgLoader.getSetupImgLoader( setupId );
		final T type = setupImgLoader.getImageType();
		final V volatileType = setupImgLoader.getVolatileImageType();

		if ( !( type instanceof NumericType ) )
			throw new IllegalArgumentException( "ImgLoader of type " + type.getClass() + " not supported." );

		final String name = BDVSourceNaming.viewIdSourceName( setup, spimData.getSequenceDescription() );

		SourceAndConverter< V > vsoc = null;
		if ( volatileType != null )
		{
			final VolatileSpimSource< V > vs = new VolatileSpimSource<>( spimData, setupId, name );
			vsoc = new SourceAndConverter<>( vs, BigDataViewer.<V>createConverterToARGB( volatileType ) );
		}
		final SpimSource< T > s = new SpimSource<>( spimData, setupId, name );
		final SourceAndConverter< T > soc = new SourceAndConverter<>( s, BigDataViewer.<T>createConverterToARGB( type ), vsoc );
		return BigDataViewer.<T, V>wrapWithTransformedSource( soc );
	}

	/**
	 * Single-source histogram-based brightness (mirrors BDV's {@code estimateBounds}:
	 * 6535 bins over [0, 65535], cumulative cutoffs 0.001 / 0.999). Type default for
	 * non-{@link UnsignedShortType}.
	 */
	private static double[] sampleAndComputeRange( final bdv.viewer.Source< ? > src, final int timepoint )
	{
		final Object type = src.getType();
		if ( type instanceof UnsignedByteType )
			return new double[]{ 0, 255 };
		if ( !( type instanceof UnsignedShortType ) )
			return new double[]{ 0, 255 };
		if ( !src.isPresent( timepoint ) )
			return new double[]{ 0, 255 };

		@SuppressWarnings( "unchecked" )
		final RandomAccessibleInterval< UnsignedShortType > img =
				( RandomAccessibleInterval< UnsignedShortType > ) src.getSource( timepoint, src.getNumMipmapLevels() - 1 );
		if ( img.numDimensions() < 3 || img.dimension( 2 ) <= 0 )
			return new double[]{ 0, 255 };

		final long z = ( img.min( 2 ) + img.max( 2 ) + 1 ) / 2;
		final int numBins = 6535;
		final long[] hist = new long[ numBins ];
		long total = 0;
		for ( final UnsignedShortType v : Views.hyperSlice( img, 2, z ) )
		{
			int bin = ( v.get() * numBins ) / 65536;
			if ( bin >= numBins )
				bin = numBins - 1;
			hist[ bin ]++;
			total++;
		}
		if ( total == 0 )
			return new double[]{ 0, 255 };

		long cum = 0;
		int i = 0;
		while ( i < numBins && ( double ) cum / total < 0.001 )
			cum += hist[ i++ ];
		final double rangeMin = i * 65535.0 / numBins;
		while ( i < numBins && ( double ) cum / total < 0.999 )
			cum += hist[ i++ ];
		final double rangeMax = i * 65535.0 / numBins;
		return new double[]{ rangeMin, rangeMax };
	}
}
