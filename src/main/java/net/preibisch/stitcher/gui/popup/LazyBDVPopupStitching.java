/*-
 * #%L
 * Multiview stitching of large datasets.
 * %%
 * Copyright (C) 2016 - 2025 Big Stitcher developers.
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
import bdv.TransformEventHandler2D;
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
 * Lazy variant of {@link BDVPopupStitching}: opens BigDataViewer with no sources
 * and adds/removes sources dynamically as the user selects rows in the explorer.
 * Extends {@link BDVPopupStitching} so it is found by {@code bdvPopup()} and all
 * existing call sites that access {@code bdvPopup().bdv} work without changes.
 */
public class LazyBDVPopupStitching extends BDVPopupStitching
{
	private static final long serialVersionUID = 1L;

	/** If true, deselected sources are removed from BDV state; if false they are hidden. */
	public static boolean removeOnDeselect = true;

	private final Map< Integer, SourceAndConverter< ? > > activeBySetupId = new HashMap<>();
	private final Map< Integer, ConverterSetup > setupBySetupId = new HashMap<>();
	private double[] sharedRange = null;
	private boolean firstTransformDone = false;
	private SelectedViewDescriptionListener< ? > registeredListener = null;
	private ExplorerWindow< ? > explorerPanel = null;

	public LazyBDVPopupStitching( final LinkOverlay lo )
	{
		super( lo );
		// BDVPopupStitching already removed BDVPopup's listener and added its own.
		// Replace that with our lazy listener.
		this.removeActionListener( this.getActionListeners()[ 0 ] );
		this.addActionListener( new MyLazyActionListener() );
		this.setText( "Display in BigDataViewer (lazy on/off)" );
	}

	public class MyLazyActionListener implements ActionListener
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
					resetLazyState();
				}
				if ( bdv == null )
					openLazyBDV( panel );
				else
					closeBDV();
			} ).start();
		}
	}

	public BigDataViewer openLazyBDV( final ExplorerWindow< ? > panel )
	{
		if ( bdv != null && bdv.getViewerFrame().isVisible() )
			return bdv;
		if ( bdv != null )
		{
			bdv = null;
			resetLazyState();
		}
		try
		{
			bdv = createLazyBDV( panel );
		}
		catch ( final Throwable ex )
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
		resetLazyState();
	}

	private void resetLazyState()
	{
		activeBySetupId.clear();
		setupBySetupId.clear();
		sharedRange = null;
		firstTransformDone = false;
		registeredListener = null;
		explorerPanel = null;
	}

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private BigDataViewer createLazyBDV( final ExplorerWindow< ? > panel )
	{
		this.explorerPanel = panel;
		final AbstractSpimData< ? > spimData = panel.getSpimData();
		final AbstractSequenceDescription< ?, ?, ? > seq = spimData.getSequenceDescription();

		boolean allViews2D = true;
		for ( final BasicViewDescription< ? > vd : seq.getViewDescriptions().values() )
			if ( vd.isPresent() && vd.getViewSetup().hasSize() && vd.getViewSetup().getSize().dimension( 2 ) != 1 )
			{
				allViews2D = false;
				break;
			}

		final ViewerOptions options = ViewerOptions.options().accumulateProjectorFactory( MaximumProjectorARGB.factory );
		if ( allViews2D )
			options.transformEventHandlerFactory( TransformEventHandler2D::new );

		final ArrayList< ConverterSetup > emptyCS = new ArrayList<>();
		final ArrayList< SourceAndConverter< ? > > emptySrc = new ArrayList<>();
		final int numTP = seq.getTimePoints().size();
		final CacheControl cache = ( ( ViewerImgLoader ) seq.getImgLoader() ).getCacheControl();
		final BigDataViewer newBdv = new BigDataViewer( emptyCS, emptySrc, spimData,
				numTP, cache, panel.xml().toString(), IOFunctions.getProgressWriter(), options );

		BDVTools.setFusedModeSimple( newBdv, spimData );
		this.bdv = newBdv;

		newBdv.getViewer().addTransformListener( lo );
		newBdv.getViewer().getDisplay().addOverlayRenderer( lo );

		ScrollableBrightnessDialog.setAsBrightnessDialog( newBdv );
		newBdv.getViewerFrame().setVisible( true );

		final FilteredAndGroupedExplorerPanel rawPanel = ( FilteredAndGroupedExplorerPanel ) panel;
		final SelectedViewDescriptionListener listener = new SelectedViewDescriptionListener()
		{
			@Override
			public void selectedViewDescriptions( final List viewDescriptions )
			{
				if ( bdv == null || !bdv.getViewerFrame().isVisible() )
					return;
				try
				{
					syncSources( spimData, ( List< List< BasicViewDescription< ? > > > ) viewDescriptions );
				}
				catch ( final Exception ex )
				{
					IOFunctions.println( "[LazyBDV-Stitching] sync failed: " + ex );
					ex.printStackTrace();
				}
			}

			@Override public void updateContent( final AbstractSpimData data ) {}
			@Override public void save() {}
			@Override public void quit() {}
		};
		rawPanel.addListener( listener );
		registeredListener = listener;

		final List< List< BasicViewDescription< ? > > > current = new ArrayList<>();
		@SuppressWarnings( "unchecked" )
		final java.util.Set< List< BasicViewDescription< ? > > > rows =
				( java.util.Set< List< BasicViewDescription< ? > > > ) ( java.util.Set ) rawPanel.selectedRows;
		for ( final List< BasicViewDescription< ? > > row : rows )
			current.add( row );
		try { syncSources( spimData, current ); }
		catch ( final Exception ex ) { ex.printStackTrace(); }

		IOFunctions.println( "[LazyBDV-Stitching] BDV opened with lazy source management." );
		return newBdv;
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
		final int sampleTP = seq.getTimePoints().getTimePointsOrdered().get( 0 ).getId();

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
				sharedRange = sampleAndComputeRange( soc.getSpimSource(), sampleTP );
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

		if ( !addList.isEmpty() || !toRemoveOrHide.isEmpty() )
		{
			if ( explorerPanel == null || !explorerPanel.colorMode() )
				colorByChannels( bdv, spimData, 0 );
			else
				BDVTools.colorSourcesBatch( bdv, 0 );
		}

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

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private static < T extends NumericType< T >, V extends Volatile< T > & NumericType< V > > SourceAndConverter< T >
			createSourceAndConverter( final AbstractSpimData< ? > spimData, final BasicViewSetup setup )
	{
		final int setupId = setup.getId();
		final ViewerImgLoader imgLoader = ( ViewerImgLoader ) spimData.getSequenceDescription().getImgLoader();
		final ViewerSetupImgLoader< T, V > setupImgLoader =
				( ViewerSetupImgLoader< T, V > ) imgLoader.getSetupImgLoader( setupId );
		final T type = setupImgLoader.getImageType();
		final V volatileType = setupImgLoader.getVolatileImageType();

		if ( !( type instanceof NumericType ) )
			throw new IllegalArgumentException( "ImgLoader of type " + type.getClass() + " not supported." );

		final String name = BDVSourceNaming.viewIdSourceName( setup, spimData.getSequenceDescription() );

		SourceAndConverter< V > vsoc = null;
		if ( volatileType != null )
		{
			final VolatileSpimSource< V > vs = new VolatileSpimSource<>( spimData, setupId, name );
			vsoc = new SourceAndConverter<>( vs, BigDataViewer.< V >createConverterToARGB( volatileType ) );
		}
		final SpimSource< T > s = new SpimSource<>( spimData, setupId, name );
		final SourceAndConverter< T > soc = new SourceAndConverter<>( s, BigDataViewer.< T >createConverterToARGB( type ), vsoc );
		return BigDataViewer.< T, V >wrapWithTransformedSource( soc );
	}

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
