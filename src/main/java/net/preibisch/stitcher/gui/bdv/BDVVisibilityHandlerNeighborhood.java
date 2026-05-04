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
package net.preibisch.stitcher.gui.bdv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import bdv.BigDataViewer;
import bdv.tools.brightness.ConverterSetup;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import util.BDVTools;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ISpimDataTableModel;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximalGroupOverlap;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.gui.StitchingExplorerPanel;
import net.preibisch.stitcher.gui.popup.BDVPopupStitching;

public class BDVVisibilityHandlerNeighborhood implements BDVVisibilityHandler
{
	private ExplorerWindow< ? > panel;
	private long colorOffset;

	public BDVVisibilityHandlerNeighborhood( ExplorerWindow< ? > panel, long colorOffset )
	{
		this.panel = panel;
		this.colorOffset = colorOffset;
	}

	@Override
	public void updateBDV()
	{
		// panel not set, do nothing
		if (panel == null)
			return;

		// BDV is not open, nothing to do
		if (panel.bdvPopup() == null || !panel.bdvPopup().bdvRunning())
			return;

		// get all selected views
		final Set< ViewId > selectedViewIds;
		if (GroupedRowWindow.class.isInstance( panel ))
		{
			selectedViewIds = ((GroupedRowWindow)panel).selectedRowsViewIdGroups().stream().collect(
					HashSet::new,
					HashSet::addAll,
					HashSet::addAll );
		}
		else
			selectedViewIds = new HashSet<>( panel.selectedRowsViewId() );

		final BigDataViewer bdv = panel.bdvPopup().getBDV();

		// first, re-color sources
		if (!panel.colorMode())
			BDVPopupStitching.colorByChannels( bdv, panel.getSpimData(), colorOffset );
		else
			StitchingExplorerPanel.colorSources( bdv.getSetupAssignments().getConverterSetups(), colorOffset );
		
		// get map vsId -> convertersetup
		final Map<Integer, ConverterSetup> setupToConverterSetup = new HashMap<>();
		for (ConverterSetup cs : bdv.getSetupAssignments().getConverterSetups())
			setupToConverterSetup.put( cs.getSetupId(), cs );

		// current TP + idx
		final int currentTimepoint = bdv.getViewer().getState().getCurrentTimepoint();
		final int currentTPId = panel.getSpimData().getSequenceDescription().getTimePoints().getTimePointsOrdered().get( currentTimepoint ).getId();

		// all vids of current timepoint
		List< ViewId > candidates = panel.getSpimData().getSequenceDescription().getViewDescriptions().keySet()
				.stream().filter( vi -> vi.getTimePointId() == currentTPId ).collect( Collectors.toList() );

		// ignore views that are filtered out via the grouped table model
		// e.g. views from another angle
		if (FilteredAndGroupedExplorerPanel.class.isInstance( panel ))
		{
			// get all filters from model
			final ISpimDataTableModel< ? > tableModel =
					( ( FilteredAndGroupedExplorerPanel< ? > ) panel ).getTableModel();
			final Map< Class< ? extends Entity >, List< ? extends Entity > > filters = tableModel.getFilters();

			// check all candidates
			candidates = candidates.stream().filter( c -> {
				for( final Entry< Class< ? extends Entity >, List< ? extends Entity > >  e : filters.entrySet())
				{
					// we checked for time points before
					if (e.getKey() == TimePoint.class)
						continue;

					// ignore view if it was hidden by filter 
					final Entity attribute = panel.getSpimData().getSequenceDescription()
							.getViewDescriptions().get( c ).getViewSetup().getAttribute( e.getKey() );
					if (!e.getValue().contains( attribute ))
						return false;
				}
				return true;
			}).collect( Collectors.toList() );
		}

		for (final ViewId vid : candidates)
		{
			// we have this view selected -> no need to re-color
			if (selectedViewIds.contains( vid ))
				continue;

			// search for (approximate) overlap between all selected views & this view
			final ArrayList< Iterable< ViewId > > comparison = new ArrayList<>();
			comparison.add( selectedViewIds );
			comparison.add( new Group< ViewId >( vid ) );
			final BoundingBox bbox = new BoundingBoxMaximalGroupOverlap< ViewId >( comparison,panel.getSpimData() ).estimate( "" );

			// overlap found
			if (bbox != null)
			{
				ConverterSetup cs = setupToConverterSetup.get( vid.getViewSetupId() );
				if (cs != null)
				{
					// set setup to gray
					cs.setColor( new ARGBType( ARGBType.rgba( 100, 100, 100, 255 ) ) );
					bdv.getViewer().getVisibilityAndGrouping().setSourceActive(
							BDVTools.getBDVSourceIndex(
									panel.getSpimData().getSequenceDescription().getViewDescriptions().get( vid ).getViewSetup(),
									panel.getSpimData() ), true );
				}
			}
		}
	}

}
