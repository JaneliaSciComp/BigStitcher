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
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import bdv.BigDataViewer;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.gui.GenericDialog;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.QualityGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.algorithm.SpimDataFilteringAndGrouping;
import net.preibisch.stitcher.algorithm.illuminationselection.BrightestViewSelection;
import net.preibisch.stitcher.algorithm.illuminationselection.IlluminationSelectionPreviewGUI;
import net.preibisch.stitcher.algorithm.illuminationselection.MeanGradientMagnitudeViewSelection;
import net.preibisch.stitcher.algorithm.illuminationselection.RelativeFRCSelection;
import net.preibisch.stitcher.algorithm.illuminationselection.ViewSelection;

public class SelectIlluminationPopup extends JMenuItem implements ExplorerWindowSetable
{
	public static boolean defaultOnlySelection = false;
	public static boolean defaultVerify = true;

	private FilteredAndGroupedExplorerPanel< ? > panel;

	public SelectIlluminationPopup()
	{
		super( "Select Best Illuminations" );
		this.addActionListener( new MyActionListener() );
	}
	
	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = ( FilteredAndGroupedExplorerPanel< ? > ) panel;
		return this;
	}

	public static ViewSelection< ViewId > getViewSelectionResult( final GenericDialog gd, final AbstractSequenceDescription< ?, ?, ? > sd )
	{
		String choice = gd.getNextChoice();
		if (choice.equals( "Pick brightest" ))
			return new BrightestViewSelection( sd );
		else if (choice.equals("Pick highest mean gradient magnitude"))
			return new MeanGradientMagnitudeViewSelection(sd);
		else if (choice.equals("Relative Fourier Ring Correlation"))
		{
			final GenericDialog gd1 = new GenericDialog( "Relative FRC Parameters" );

			gd1.addCheckbox( "Relative_FRC", QualityGUI.defaultUseRelativeFRC );
			gd1.addNumericField( "FRC_FFT_Size", QualityGUI.defaultFFTSize, 0 );
			gd1.addNumericField( "FRC_Stepsize (z)", QualityGUI.defaultFRCStepSize, 0 );

			gd1.showDialog();

			if ( gd1.wasCanceled() )
				return null;

			final boolean useRelativeFRC = QualityGUI.defaultUseRelativeFRC = gd1.getNextBoolean();
			final int fftSize = QualityGUI.defaultFFTSize = Math.max( 16, (int)Math.round( gd1.getNextNumber() ) );
			final int frcStepSize = QualityGUI.defaultFRCStepSize = Math.max( 1, (int)Math.round( gd1.getNextNumber() ) );

			return new RelativeFRCSelection( sd, frcStepSize, fftSize, useRelativeFRC );
		}
		else
			return null;
		
	}
	
	public static void addViewSelectionQuery(GenericDialog gd)
	{
		final String[] choices = new String[] {"Pick brightest", "Pick highest mean gradient magnitude", "Relative Fourier Ring Correlation" };
		gd.addChoice( "Selection Method", choices, choices[0] );
	}
	
	private class MyActionListener implements ActionListener
	{

		@Override
		public void actionPerformed(ActionEvent e)
		{
			
			new Thread(new Runnable()
			{
				
				@Override
				public void run()
				{

					if (!SpimData2.class.isInstance( panel.getSpimData() ) )
					{
						IOFunctions.println(new Date( System.currentTimeMillis() ) + "ERROR: expected SpimData2, but got " + panel.getSpimData().getClass().getSimpleName());
						return;
					}

					final BigDataViewer bdv = panel.bdvPopup().getBDV();
					final Collection< List< BasicViewDescription< ? > > > selected = ((GroupedRowWindow)panel).selectedRowsGroups();

					SpimData2 filteredSpimData = processIlluminationSelection( 
							panel.getSpimData(),
							selected.stream().reduce( new ArrayList<>(), (x,y) -> {x.addAll( y ); return x;}),
							true,
							bdv != null,
							bdv );

					if (filteredSpimData != null)
					{
						panel.setSpimData( filteredSpimData );
						panel.updateContent();
					}

					// TODO: close and re-open BDV >> Should be solved
					final BDVPopup popup = panel.bdvPopup();

					if ( popup.bdvRunning() )
					{
						popup.closeBDV();
						popup.doClick();
					}
				}

			}).start();
		}
		
	}
	
	public static SpimData2 processIlluminationSelection(
			SpimData2 data,
			Collection< ? extends BasicViewDescription< ? > > selected,
			boolean showOnlySelectedOption,
			boolean showPreviewOption,
			BigDataViewer bdvForPreview
			)
	{
		final GenericDialogPlus gdpParams = new GenericDialogPlus( "Illumination Selection" );
		if (showOnlySelectedOption)
			gdpParams.addCheckbox( "Process only selection", defaultOnlySelection );
		if (showPreviewOption)
			gdpParams.addCheckbox( "Show selection results before applying", defaultVerify );
		addViewSelectionQuery( gdpParams );

		gdpParams.showDialog();

		if (gdpParams.wasCanceled())
			return null;

		// in the default case, we only process selection
		final boolean limitToSelection = showOnlySelectedOption ? defaultOnlySelection = gdpParams.getNextBoolean() : true;
		// in the default case, we do NOT preview
		final boolean previewResults = showPreviewOption ? defaultVerify = gdpParams.getNextBoolean() : false;
		final ViewSelection< ViewId > viewSelection = getViewSelectionResult( gdpParams, data.getSequenceDescription() );

		final SpimDataFilteringAndGrouping< SpimData2 > grouping = new SpimDataFilteringAndGrouping<>( data );
		grouping.addGroupingFactor( Illumination.class );


		if (limitToSelection)
		{
			grouping.addFilters( selected );
		}

		// get grouped views and filter out missing views
		final List< Group< BasicViewDescription< ? > > > groupedViews = grouping.getGroupedViews( true );
		groupedViews.forEach( g -> SpimData2.filterMissingViews( data, g.getViews() ) );

		// sort by the first viewid
		Collections.sort( groupedViews, new Comparator< Group< BasicViewDescription< ? > > >()
		{
			@Override
			public int compare(
					Group< BasicViewDescription< ? > > o1,
					Group< BasicViewDescription< ? > > o2 )
			{
				if ( o1 == null || o2 == null || o1.size() == 0 || o2.size() == 0 )
					return 	0;
				else
					return Group.getViewsSorted( o1.getViews() ).get( 0 ).compareTo( Group.getViewsSorted( o2.getViews() ).get( 0 ) );
			}
		} );

		// multithreaded best illuination determination
		final List< Callable< ViewId > > tasks = new ArrayList<>();
		List< ViewId > bestViews = new ArrayList<>();

		final ExecutorService service;

		if ( viewSelection.runMultithreaded() )
			service = Executors.newFixedThreadPool(Math.max( 2, Runtime.getRuntime().availableProcessors() ));
		else
			service = Executors.newFixedThreadPool( 1 );

		final AtomicInteger progress = new AtomicInteger( 0 );
		final int numTasks = groupedViews.size();

		IJ.showProgress( 0.0 );

		for (final Group<? extends ViewId > group : groupedViews)
			tasks.add( new Callable< ViewId >()
			{
				@Override
				public ViewId call() throws Exception
				{
					final ViewId best = viewSelection.getBestView( group.getViews() );

					IJ.showProgress( (double)progress.incrementAndGet() / numTasks );

					return best;
				}
			} );
		List< Future< ViewId > > futures;
		try
		{
			futures = service.invokeAll( tasks );
			for (Future< ViewId > f : futures)
				bestViews.add( f.get() );
		}
		catch ( InterruptedException | ExecutionException e )
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		service.shutdown();

		IJ.showProgress( 1.0 );

		final List< List< BasicViewDescription< ? > > > groupedViewsList = groupedViews.stream().map( g -> {
			final ArrayList<BasicViewDescription< ? >> views = new ArrayList<>(g.getViews());
			Collections.sort( views );
			return views;
		} ).collect(Collectors.toList());

		if (previewResults)
		{
			List< ViewId > bestViewsFromGUI = new IlluminationSelectionPreviewGUI().previewWithGUI( groupedViewsList, bestViews, bdvForPreview);
			if (bestViewsFromGUI == null)
			{
				System.out.println( "Illumination selection aborted by user." );
				return null;
			}
			else
				bestViews = bestViewsFromGUI;
		}

		final Set<ViewId> missingViews = new HashSet<>();
		for (final Group< ? extends ViewId > group : groupedViews)
			for (final ViewId vid : group)
				if (!(bestViews.contains( vid )))
					missingViews.add( vid );
		missingViews.forEach( System.out::println );

		SpimData2 dataNew = getCopyWithMissingViews( data, missingViews );
		return dataNew;

	}

	public static SpimData2 getCopyWithMissingViews(SpimData2 data, Collection< ? extends ViewId > missingViews)
	{
		return getCopyWithMissingViews( data, missingViews, false );
	}

	public static SpimData2 getCopyWithMissingViews(SpimData2 data, Collection< ? extends ViewId > missingViews, boolean ignoreOldMissingViews)
	{
		final URI basePath = data.getBasePathURI();
		final ViewRegistrations viewRegistrations = data.getViewRegistrations();
		
		final SequenceDescription sdOld = data.getSequenceDescription();
		
		final List<ViewId> missingViewsList = new ArrayList<>( missingViews );
		
		if (!ignoreOldMissingViews && sdOld.getMissingViews() != null )
			sdOld.getMissingViews().getMissingViews().forEach( mv -> missingViewsList.add( mv ) );
		
		final SequenceDescription sequenceDescription = 
				new SequenceDescription( 
						sdOld.getTimePoints(),
						sdOld.getViewSetupsOrdered(),
						sdOld.getImgLoader(),
						new MissingViews( missingViewsList ) );
		
		final ViewInterestPoints viewsInterestPoints = data.getViewInterestPoints();
		//if (!(SpimData2.class.isInstance( data )))
		//	viewsInterestPoints.createViewInterestPoints( data.getSequenceDescription().getViewDescriptions() );
		
		final BoundingBoxes boundingBoxes = data.getBoundingBoxes();
		final PointSpreadFunctions psfs = data.getPointSpreadFunctions();
		final StitchingResults stitchingResults = data.getStitchingResults();
		final IntensityAdjustments ia = data.getIntensityAdjustments();

		final SpimData2 dataNew = new SpimData2( basePath, sequenceDescription, viewRegistrations, viewsInterestPoints, boundingBoxes, psfs, stitchingResults, ia );
		return dataNew;
	}

	static String getViewDescriptionStringWithoutIllum(BasicViewDescription< ? > vd)
	{
		return "TP: " + vd.getTimePointId() + 
				", Tile " + vd.getViewSetup().getAttribute( Tile.class ).getId() +
				", Angle " + vd.getViewSetup().getAttribute( Angle.class ).getId() +
				", Channel " + vd.getViewSetup().getAttribute( Channel.class ).getId();
	}
}
