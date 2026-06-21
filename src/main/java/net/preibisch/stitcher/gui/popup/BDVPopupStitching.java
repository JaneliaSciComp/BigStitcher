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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import javax.swing.JOptionPane;

import bdv.BigDataViewer;
import bdv.SpimSource;
import bdv.TransformEventHandler2D;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.ViewerOptions;
import bdv.viewer.state.SourceGroup;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.bdv.ScrollableBrightnessDialog;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AbstractImgLoader;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.gui.MaximumProjectorARGB;
import net.preibisch.stitcher.gui.overlay.LinkOverlay;
import util.BDVTools;
import util.ColorStream;

public class BDVPopupStitching extends BDVPopup
{
	/**
	 * 
	 */
	private static final long serialVersionUID = -8852442192041303045L;

	private LinkOverlay lo;

	public BDVPopupStitching(LinkOverlay lo)
	{
		super();
		this.lo = lo;
		this.removeActionListener( this.getActionListeners()[0] );
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

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					// if BDV was closed by the user
					if ( bdv != null && !bdv.getViewerFrame().isVisible() )
						bdv = null;

					if ( bdv == null )
					{

						try
						{
							bdv = createBDV( panel, lo );
						}
						catch (Exception ex)
						{
							IOFunctions.println( "Could not run BigDataViewer: " + ex );
							ex.printStackTrace( System.err );
							bdv = null;
						}
					}
					else
					{
						closeBDV();
					}
				}
			}).start();
		}
	}

	public static void minMaxGroupByChannels(BigDataViewer bdv, AbstractSpimData< ? > data)
	{
		Set< Class< ? extends Entity> > groupingFactors = new HashSet<>();
		groupingFactors.add( Channel.class );
		groupingFactors.add( Illumination.class );
		minMaxGroupByFactors( bdv, data, groupingFactors );
	}

	public static void minMaxGroupByFactors(BigDataViewer bdv, AbstractSpimData< ? > data, Set<Class<? extends Entity>> groupingFactors)
	{
		List<BasicViewDescription< ? > > vds = new ArrayList<>();
		Map<BasicViewDescription< ? >, ConverterSetup> vdToCs = new HashMap<>();

		for (ConverterSetup cs : bdv.getSetupAssignments().getConverterSetups())
		{
			Integer timepointId = data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( bdv.getViewer().getState().getCurrentTimepoint()).getId();
			BasicViewDescription< ? > vd = data.getSequenceDescription().getViewDescriptions().get( new ViewId( timepointId, cs.getSetupId() ) );

			// no view description for this setup at the current timepoint -> skip
			// (otherwise the null propagates into Group.splitBy and breaks the sort)
			if ( vd == null )
				continue;

			vds.add( vd );
			vdToCs.put( vd, cs );
		}

		List< Group< BasicViewDescription< ? > > > vdGroups = Group.splitBy( vds, groupingFactors );

		// nothing to group
		if (vdGroups.size() <= 1)
			return;
		List<ArrayList<ConverterSetup>> groups =  new ArrayList<>();

		for (Group< BasicViewDescription< ? > > lVd : vdGroups)
		{
			ArrayList< ConverterSetup > lCs = new ArrayList<>();
			for (BasicViewDescription< ? > vd : lVd)
				lCs.add( vdToCs.get( vd ) );
			groups.add( lCs );
		}

		for (int i = 1; i < groups.size(); i++)
		{
			ArrayList<ConverterSetup> cs = groups.get( i );
			// remove first setup from its group (group 0), creating a new one
			bdv.getSetupAssignments().removeSetupFromGroup( cs.get( 0 ), bdv.getSetupAssignments().getMinMaxGroups().get( 0 ));

			// move all other setups in group to the new MinMaxGroup (group i)
			for (int j = 1; j < cs.size(); ++j)
			{
				bdv.getSetupAssignments().moveSetupToGroup( cs.get( j ), bdv.getSetupAssignments().getMinMaxGroups().get( i ) );
			}
		}
	}

	public static void groupSourcesByFactors(BigDataViewer bdv, AbstractSpimData< ? > data,
			Set< Class< ? extends Entity > > groupingFactors)
	{
		//  clear source groups		
		for (int i = 0; i < bdv.getViewer().getState().numSourceGroups(); ++i)
		{
			SourceGroup sg = bdv.getViewer().getState().getSourceGroups().get( i );
			SortedSet< Integer > sourceIds = sg.getSourceIds();
			for (Integer si: sourceIds)
				bdv.getViewer().getVisibilityAndGrouping().removeSourceFromGroup( si, i );
		}

		final List<BasicViewDescription< ? > > vds = new ArrayList<>();
		final Map<BasicViewDescription< ? >, Integer> vdToSource = new HashMap<>();
		

		for(int i = 0; i < bdv.getViewer().getState().getSources().size(); ++i)
		{
			Integer timepointId = data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( bdv.getViewer().getState().getCurrentTimepoint()).getId();
			
			SpimSource<?> src = (SpimSource< ? >)((TransformedSource< ? >)bdv.getViewer().getState().getSources().get( i ).getSpimSource()).getWrappedSource();
			BasicViewDescription< ? > vd = data.getSequenceDescription().getViewDescriptions().get( new ViewId( timepointId, src.getSetupId()) );

			// no view description for this source at the current timepoint -> skip
			// (otherwise the null propagates into Group.combineBy and breaks the sort)
			if ( vd == null )
				continue;

			vds.add( vd );
			vdToSource.put( vd, i );
		}

		final List< Group< BasicViewDescription< ? > > > groupByAttributes = Group.combineBy( vds, groupingFactors );
		
		for (int gi = 0; gi < groupByAttributes.size(); gi ++)
		{
			Group< BasicViewDescription< ? > > vdsI = groupByAttributes.get( gi );
			for (BasicViewDescription< ? > vd : vdsI)
			{
				bdv.getViewer().getVisibilityAndGrouping().addSourceToGroup( vdToSource.get( vd ), gi );
			}
		}
	}

	public static void colorByChannels(BigDataViewer bdv, AbstractSpimData< ? > data, long cOffset)
	{
		Set< Class< ? extends Entity> > groupingFactors = new HashSet<>();
		groupingFactors.add( Channel.class );
		groupingFactors.add( Illumination.class );
		colorByFactors( bdv, data, groupingFactors, cOffset );
	}

	public static void colorByFactors(BigDataViewer bdv, AbstractSpimData< ? > data, Set<Class<? extends Entity>> groupingFactors, long cOffset)
	{
		final List<BasicViewDescription< ? > > vds = new ArrayList<>();
		final Map<BasicViewDescription< ? >, ConverterSetup> vdToCs = new HashMap<>();

		for (ConverterSetup cs : bdv.getSetupAssignments().getConverterSetups())
		{
			Integer timepointId = data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( bdv.getViewer().getState().getCurrentTimepoint()).getId();
			BasicViewDescription< ? > vd = data.getSequenceDescription().getViewDescriptions().get( new ViewId( timepointId, cs.getSetupId() ) );

			// no view description for this setup at the current timepoint -> skip
			// (otherwise the null propagates into Group.splitBy and breaks the sort)
			if ( vd == null )
				continue;

			vds.add( vd );
			vdToCs.put( vd, cs );
		}

		final List< Group< BasicViewDescription< ? > > > vdGroups = Group.splitBy( vds, groupingFactors );

		// nothing to group
		if (vdGroups.size() < 1)
			return;

		// one group -> white
		if (vdGroups.size() == 1)
		{
			BDVTools.whiteSources(bdv.getSetupAssignments().getConverterSetups());
			ScrollableBrightnessDialog.updateBrightnessPanels( bdv );
			return;
		}

		final List<ArrayList<ConverterSetup>> groups =  new ArrayList<>();

		for (Group< BasicViewDescription< ? > > lVd : vdGroups)
		{
			ArrayList< ConverterSetup > lCs = new ArrayList<>();
			for (BasicViewDescription< ? > vd : lVd)
				lCs.add( vdToCs.get( vd ) );
			groups.add( lCs );
		}

		Iterator< ARGBType > colorIt = ColorStream.iterator();
		for (long i = 0; i<cOffset; ++i)
			colorIt.next();

		for (ArrayList< ConverterSetup > csg : groups)
		{
			ARGBType color = colorIt.next();
			for (ConverterSetup cs : csg)
				cs.setColor( color );
		}

		ScrollableBrightnessDialog.updateBrightnessPanels( bdv );
	}

	public static BigDataViewer createBDV( final ExplorerWindow< ? > panel , LinkOverlay lo)
	{
		if ( AbstractImgLoader.class.isInstance( panel.getSpimData().getSequenceDescription().getImgLoader() ) )
		{
			if ( JOptionPane.showConfirmDialog( null,
					"Opening <SpimData> dataset that is not suited for interactive browsing.\n" +
					"Consider resaving as HDF5 for better performance.\n" +
					"Proceed anyways?",
					"Warning",
					JOptionPane.YES_NO_OPTION ) == JOptionPane.NO_OPTION )
				return null;
		}

		boolean allViews2D = true;
		final Collection< ViewDescription > viewDescriptions =
			panel.getSpimData().getSequenceDescription().getViewDescriptions().values();

		for (final BasicViewDescription< ? > vd : viewDescriptions)
			if (vd.isPresent() && vd.getViewSetup().hasSize() && vd.getViewSetup().getSize().dimension( 2 ) != 1)
			{
				allViews2D = false;
				break;
			}

		final ViewerOptions options = ViewerOptions.options().accumulateProjectorFactory( MaximumProjectorARGB.factory );
		if (allViews2D)
		{
			options.transformEventHandlerFactory( TransformEventHandler2D::new );
		}

		BigDataViewer bdv = BigDataViewer.open( panel.getSpimData(), 
												"BigDataViewer", 
												null, 
												options );

		BDVPopup.initTransform( bdv.getViewer() );
		if ( !bdv.tryLoadSettings( panel.xml().toString() ) )
			BDVPopup.initBrightness( 0.001, 0.999, bdv.getViewerFrame() );

		BDVTools.setFusedModeSimple( bdv, panel.getSpimData() );

		minMaxGroupByChannels( bdv, panel.getSpimData() );
		colorByChannels( bdv, panel.getSpimData(), 0 );

		Set<Class<? extends Entity>> groupingFactors = new HashSet<>();
		groupingFactors.add( Channel.class );
		groupingFactors.add( Illumination.class );		
		groupSourcesByFactors( bdv, panel.getSpimData(), groupingFactors );

		FilteredAndGroupedExplorerPanel.updateBDV( bdv, panel.colorMode(), panel.getSpimData(), panel.firstSelectedVD(), ((GroupedRowWindow)panel).selectedRowsGroups());

		ScrollableBrightnessDialog.setAsBrightnessDialog( bdv );

		bdv.getViewer().addTransformListener( lo );
		bdv.getViewer().getDisplay().addOverlayRenderer( lo );
		
		bdv.getViewerFrame().setVisible( true );		
		bdv.getViewer().requestRepaint();

		return bdv;
		
	}

	@Override
	public void setBDV(BigDataViewer existingBdv)
	{
		// close existing bdv if necessary
		if (bdvRunning())
			new Thread(() -> {closeBDV();}).start();

		this.bdv = existingBdv;
		BDVTools.setFusedModeSimple( bdv, panel.getSpimData() );

		minMaxGroupByChannels( bdv, panel.getSpimData() );
		colorByChannels( bdv, panel.getSpimData(), 0);

		Set<Class<? extends Entity>> groupingFactors = new HashSet<>();
		groupingFactors.add( Channel.class );
		groupingFactors.add( Illumination.class );		
		groupSourcesByFactors( bdv, panel.getSpimData(), groupingFactors );

		FilteredAndGroupedExplorerPanel.updateBDV( bdv, panel.colorMode(), panel.getSpimData(), panel.firstSelectedVD(), ((GroupedRowWindow)panel).selectedRowsGroups());

		bdv.getViewer().removeTransformListener( lo );
		bdv.getViewer().addTransformListener( lo );
		bdv.getViewer().getDisplay().addOverlayRenderer( lo );
		
		bdv.getViewerFrame().setVisible( true );		
		bdv.getViewer().requestRepaint();
	}
}
