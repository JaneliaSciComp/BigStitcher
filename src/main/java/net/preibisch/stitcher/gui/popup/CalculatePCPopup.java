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

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.stitcher.algorithm.PairwiseStitchingParameters;
import net.preibisch.stitcher.algorithm.SpimDataFilteringAndGrouping;
import net.preibisch.stitcher.algorithm.GroupedViewAggregator.ActionType;
import net.preibisch.stitcher.algorithm.lucaskanade.LucasKanadeParameters;
import net.preibisch.stitcher.gui.StitchingExplorerPanel;
import net.preibisch.stitcher.gui.StitchingUIHelper;
import net.preibisch.stitcher.plugin.Calculate_Pairwise_Shifts;

public class CalculatePCPopup extends JMenuItem implements ExplorerWindowSetable
{
	/**
	 * 
	 */
	private static final long serialVersionUID = 8664967345630864576L;
	
	public enum Method{
		PHASECORRELATION,
		LUCASKANADE
	}

	private ExplorerWindow< ? > panel;
	private boolean simple;
	private boolean wizardMode;
	private Method method;

	public CalculatePCPopup(String description, boolean simple, Method method, boolean wizardMode)
	{
		super( description );
		this.simple = simple;
		this.method = method;
		this.wizardMode = wizardMode;
		this.addActionListener( new MyActionListener() );
	}

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	public class MyActionListener implements ActionListener
	{

		@Override
		public void actionPerformed(ActionEvent e)
		{

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{

					if (!SpimData2.class.isInstance( panel.getSpimData() ) )
					{
						IOFunctions.println(new Date( System.currentTimeMillis() ) + "ERROR: expected SpimData2, but got " + panel.getSpimData().getClass().getSimpleName());
						return;
					}

					PairwiseStitchingParameters params = null;
					LucasKanadeParameters LKParams = null;
					if (method == Method.PHASECORRELATION)
						params = simple ? new PairwiseStitchingParameters() : PairwiseStitchingParameters.askUserForParameters();
					if (method == Method.LUCASKANADE)
						LKParams = LucasKanadeParameters.askUserForParameters();

					if (params == null && LKParams == null)
						return;

					final boolean expertGrouping = method == Method.PHASECORRELATION ? params.showExpertGrouping : LKParams.showExpertGrouping;

					@SuppressWarnings("unchecked")
					FilteredAndGroupedExplorerPanel< SpimData2 > panelFG = (FilteredAndGroupedExplorerPanel< SpimData2 >) panel;
					SpimDataFilteringAndGrouping< SpimData2 > filteringAndGrouping = 	new SpimDataFilteringAndGrouping<>( (SpimData2) panel.getSpimData() );

					if (simple || !expertGrouping)
					{
						// use whatever is selected in panel as filters
						filteringAndGrouping.addFilters( panelFG.selectedRowsGroups().stream().reduce( new ArrayList<>(), (x,y ) -> {x.addAll( y ); return x;}) );
					}
					else
					{
						filteringAndGrouping.askUserForFiltering( panelFG );
						if (filteringAndGrouping.getDialogWasCancelled())
							return;
					}

					if (simple || !expertGrouping)
					{
						// get the grouping from panel and compare Tiles
						panelFG.getTableModel().getGroupingFactors().forEach( g -> filteringAndGrouping.addGroupingFactor( g ));
						filteringAndGrouping.addComparisonAxis( Tile.class );

						// compare by Channel if channels were ungrouped in UI
						if (!panelFG.getTableModel().getGroupingFactors().contains( Channel.class ))
							filteringAndGrouping.addComparisonAxis( Channel.class );

						// compare by Illumination if illums were ungrouped in UI
						if (!panelFG.getTableModel().getGroupingFactors().contains( Illumination.class ))
							filteringAndGrouping.addComparisonAxis( Illumination.class );
					}
					else
					{
						filteringAndGrouping.addComparisonAxis( Tile.class );
						filteringAndGrouping.askUserForGrouping( panelFG );
						if (filteringAndGrouping.getDialogWasCancelled())
							return;
					}

					// ask user what to do with grouped views
					// use AVERAGE as pre-set choice for illuminations
					// (this will cause no overhead if only one illum is present)
					final HashMap< Class<? extends Entity>, ActionType > illumDefaultAggregation = new HashMap<>();
					illumDefaultAggregation.put( Illumination.class, ActionType.AVERAGE );

					if (simple)
						filteringAndGrouping.askUserForGroupingAggregator(illumDefaultAggregation);
					else
						filteringAndGrouping.askUserForGroupingAggregator();

					if (filteringAndGrouping.getDialogWasCancelled())
						return;

					boolean allViews2D = StitchingUIHelper.allViews2D( filteringAndGrouping.getFilteredViews() );
					long[] dsFactors = StitchingUIHelper.askForDownsampling( panel.getSpimData(), allViews2D );
					if (dsFactors == null)
						return;

					if (method == Method.PHASECORRELATION)
						Calculate_Pairwise_Shifts.processPhaseCorrelation( (SpimData2) panel.getSpimData(), filteringAndGrouping, params, dsFactors );
					if (method == Method.LUCASKANADE)
						Calculate_Pairwise_Shifts.processLucasKanade( (SpimData2) panel.getSpimData(), filteringAndGrouping, LKParams, dsFactors );


					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": DONE." );

					if (wizardMode)
					{
						// remember if we used default parameters here -> we will use default parameters in global opt as well.
						filteringAndGrouping.requestExpertSettingsForGlobalOpt = !simple;

						// ask user if they want to switch to preview mode
						if (panel instanceof StitchingExplorerPanel)
						{
							final int choice = JOptionPane.showConfirmDialog( (Component) panel, "Pairwise shift calculation done. Switch to preview mode?", "Preview Mode", JOptionPane.YES_NO_OPTION );
							if (choice == JOptionPane.YES_OPTION)
							{
								((StitchingExplorerPanel< ? >) panel).setSavedFilteringAndGrouping( filteringAndGrouping );
								((StitchingExplorerPanel< ? >) panel).togglePreviewMode(false);
							}
						}
					}
				}

			} ).start();


		}
	}
}
