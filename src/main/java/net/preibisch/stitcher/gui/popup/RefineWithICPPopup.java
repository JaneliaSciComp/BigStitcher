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

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;

import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;

import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters.PreAlign;
import net.preibisch.mvrecon.fiji.plugin.util.MouseOverPopUpStateChanger;
import net.preibisch.mvrecon.fiji.plugin.util.MouseOverPopUpStateChanger.StateChanger;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.Separator;
import net.preibisch.stitcher.algorithm.SpimDataFilteringAndGrouping;
import net.preibisch.stitcher.gui.overlay.DemoLinkOverlay;
import net.preibisch.stitcher.process.ICPRefinement;
import net.preibisch.stitcher.process.ICPRefinement.ICPRefinementParameters;
import net.preibisch.stitcher.process.ICPRefinement.ICPType;

public class RefineWithICPPopup extends JMenu implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 1L;

	DemoLinkOverlay overlay;
	ExplorerWindow< ? > panel;

	int downsamplingChoice = ICPRefinement.defaultDownsamplingChoice;
	int thresholdChoice = ICPRefinement.defaultThresholdChoice;
	int distanceChoice = ICPRefinement.defaultDistanceChoice;

	public RefineWithICPPopup( String description, final DemoLinkOverlay overlay )
	{
		super( description );

		this.overlay = overlay;

		final JMenuItem simpleICPtiles = new JMenuItem( ICPRefinement.refinementType[ 0 ] );
		final JMenuItem simpleICPchannels = new JMenuItem( ICPRefinement.refinementType[ 1 ] );
		final JMenuItem simpleICPall = new JMenuItem( ICPRefinement.refinementType[ 2 ] );
		final JMenuItem advancedICP = new JMenuItem( ICPRefinement.refinementType[ 3 ] );

		simpleICPtiles.addActionListener( new ICPListener( ICPType.TileRefine ) );
		simpleICPchannels.addActionListener( new ICPListener( ICPType.ChromaticAbberation ) );
		simpleICPall.addActionListener( new ICPListener( ICPType.All ) );
		advancedICP.addActionListener( new ICPListener( ICPType.Expert ) );

		this.add( simpleICPtiles );
		this.add( simpleICPchannels );
		this.add( simpleICPall );
		this.add( advancedICP );

		this.add( new Separator() );

		final JMenuItem[] dsItems = new JMenuItem[ ICPRefinement.downsampling.length ];
		final StateChanger dsStateChanger = new StateChanger() { public void setSelectedState( int state ) { downsamplingChoice = ICPRefinement.defaultDownsamplingChoice = state; } };

		for ( int i = 0; i < dsItems.length; ++i )
		{
			final JMenuItem item = new JMenuItem( ICPRefinement.downsampling[ i ] );

			if ( i == downsamplingChoice )
				item.setForeground( Color.RED );
			else
				item.setForeground( Color.GRAY );

			dsItems[ i ] = item;
		}

		for ( int i = 0; i < dsItems.length; ++i )
		{
			final JMenuItem item = dsItems[ i ];
			final MouseOverPopUpStateChanger cds = new MouseOverPopUpStateChanger( dsItems, i, dsStateChanger );

			item.addActionListener( cds );
			item.addMouseListener( cds );
			this.add( item );
		}

		this.add( new Separator() );

		final JMenuItem[] thrItems = new JMenuItem[ ICPRefinement.threshold.length ];
		final StateChanger thrStateChanger = new StateChanger() { public void setSelectedState( int state ) { thresholdChoice = ICPRefinement.defaultThresholdChoice = state; } };

		for ( int i = 0; i < thrItems.length; ++i )
		{
			final JMenuItem item = new JMenuItem( ICPRefinement.threshold[ i ] );

			if ( i == thresholdChoice )
				item.setForeground( Color.RED );
			else
				item.setForeground( Color.GRAY );

			thrItems[ i ] = item;
		}

		for ( int i = 0; i < thrItems.length; ++i )
		{
			final JMenuItem item = thrItems[ i ];
			final MouseOverPopUpStateChanger cds = new MouseOverPopUpStateChanger( thrItems, i, thrStateChanger );

			item.addActionListener( cds );
			item.addMouseListener( cds );
			this.add( item );
		}

		this.add( new Separator() );

		final JMenuItem[] distItems = new JMenuItem[ ICPRefinement.distance.length ];
		final StateChanger distStateChanger = new StateChanger()
		{ 
			public void setSelectedState( int state )
			{
				distanceChoice = ICPRefinement.defaultDistanceChoice = state;

				if ( distanceChoice == 0 )
					ICPRefinement.defaultICPError = 1.0;
				else if ( distanceChoice == 1 )
					ICPRefinement.defaultICPError = 5.0;
				else
					ICPRefinement.defaultICPError = 20;
			}
		};

		for ( int i = 0; i < distItems.length; ++i )
		{
			final JMenuItem item = new JMenuItem( ICPRefinement.distance[ i ] );

			if ( i == distanceChoice )
				item.setForeground( Color.RED );
			else
				item.setForeground( Color.GRAY );

			distItems[ i ] = item;
		}

		for ( int i = 0; i < distItems.length; ++i )
		{
			final JMenuItem item = distItems[ i ];
			final MouseOverPopUpStateChanger cds = new MouseOverPopUpStateChanger( distItems, i, distStateChanger );

			item.addActionListener( cds );
			item.addMouseListener( cds );
			this.add( item );
		}
	}

	@Override
	public JComponent setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;

		return this;
	}

	public class ICPListener implements ActionListener
	{
		final ICPType icpType;

		public ICPListener( final ICPType icpType )
		{
			this.icpType = icpType;
		}

		@Override
		public void actionPerformed( ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			if ( !SpimData2.class.isInstance( panel.getSpimData() ) )
			{
				IOFunctions.println( "Only supported for SpimData2 objects: " + this.getClass().getSimpleName() );
				return;
			}

			if (!GroupedRowWindow.class.isInstance( panel ))
			{
				IOFunctions.println( "Only supported for GroupedRowWindow panels: " + this.getClass().getSimpleName() );
				return;
			}

			new Thread( () ->
			{
				// get selected groups, filter missing views, get all present and selected vids
				final SpimData2 data = (SpimData2) panel.getSpimData();
				@SuppressWarnings("unchecked")
				FilteredAndGroupedExplorerPanel< SpimData2 > panelFG = (FilteredAndGroupedExplorerPanel< SpimData2 >) panel;
				SpimDataFilteringAndGrouping< SpimData2 > filteringAndGrouping = 	new SpimDataFilteringAndGrouping<>( (SpimData2) panel.getSpimData() );

				// use whatever is selected in panel as filters
				filteringAndGrouping.addFilters( panelFG.selectedRowsGroups().stream().reduce( new ArrayList<>(), (x,y ) -> {x.addAll( y ); return x;}) );

				final ICPRefinementParameters params = ICPRefinement.initICPRefinement( data, filteringAndGrouping.getFilteredViews() );

				if ( params == null )
					return;

				// remember for macro recording
				ICPRefinement.defaultRefinementChoice = icpType.ordinal();
				GlobalOptimizationParameters globalOptParams;

				if ( icpType == ICPType.Expert )
				{
					if ( !ICPRefinement.getGUIParametersAdvanced( data, params ) )
						return;

					globalOptParams = GlobalOptimizationParameters.askUserForSimpleParameters();
				}
				else
				{
					if ( !ICPRefinement.getGUIParametersSimple( icpType, data, params, downsamplingChoice, thresholdChoice, distanceChoice ) )
						return;

					globalOptParams = GlobalOptimizationParameters.getGlobalOptimizationParametersForSelection( GlobalOptimizationParameters.defaultSimple, false );
				}

				if ( globalOptParams == null )
					return;

				ICPRefinement.refine( data, params, globalOptParams, overlay );

				panel.updateContent();
				panel.bdvPopup().updateBDV();
			}).start();
		}
		
	}
}
