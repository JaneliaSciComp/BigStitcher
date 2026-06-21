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
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.algorithm.FilteredStitchingResults;
import net.preibisch.stitcher.gui.StitchingExplorerPanel;
import net.preibisch.stitcher.gui.overlay.DemoLinkOverlay;

public class SimpleRemoveLinkPopup extends JMenuItem implements ExplorerWindowSetable
{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	ExplorerWindow< ? > panel;

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	public static boolean filterPairwiseShifts(
			SpimData2 data,
			boolean considerSelection,
			List< List<ViewId> > selectedViewGroups,
			final DemoLinkOverlay demoOverlay
			)
	{
		final GenericDialog gd = new GenericDialog("Filter Pairwise Registrations");

		gd.addCheckbox("filter_by_link_quality", false);
		gd.addNumericField("min_R", 0.0, 2);
		gd.addNumericField("max_R", 1.0, 2);

		gd.addCheckbox("filter_by_shift_in_each_dimension", false);
		gd.addNumericField("max_shift_in_X", 0.0, 2);
		gd.addNumericField("max_shift_in_Y", 0.0, 2);
		gd.addNumericField("max_shift_in_Z", 0.0, 2);

		gd.addCheckbox("filter_by_total_shift_magnitude", false);
		gd.addNumericField("max_displacement", 0.0, 2);

		if (considerSelection)
			gd.addCheckbox("remove_only_links_between_selected_views", true);

		gd.showDialog();

		if (gd.wasCanceled())
			return false;

		final boolean doCorrelationFilter = gd.getNextBoolean();
		final double minR = gd.getNextNumber();
		final double maxR = gd.getNextNumber();

		final boolean doAbsoluteShiftFilter = gd.getNextBoolean();
		final double[] maxShift = new double[]{gd.getNextNumber(), gd.getNextNumber(), gd.getNextNumber()};

		final boolean doMagnitudeFilter = gd.getNextBoolean();
		final double maxMag = gd.getNextNumber();

		final boolean onlySelectedLinks = considerSelection ? gd.getNextBoolean() : false;

		final Set<Pair<Group<ViewId>, Group<ViewId>>> selectedPairs = new HashSet<>();
		if (onlySelectedLinks)
		{
			for (int i = 0; i < selectedViewGroups.size(); i++)
				for (int j = i+1; j< selectedViewGroups.size() -1; j++)
				{
					// add both ways just to make sure
					selectedPairs.add(new ValuePair<>(new Group<>(selectedViewGroups.get(i)), new Group<>(selectedViewGroups.get(j))));
					selectedPairs.add(new ValuePair<>(new Group<>(selectedViewGroups.get(j)), new Group<>(selectedViewGroups.get(i))));
				}
		}

		FilteredStitchingResults fsr = new FilteredStitchingResults(data.getStitchingResults(), demoOverlay );

		if (doCorrelationFilter)
			fsr.addFilter(new FilteredStitchingResults.CorrelationFilter(minR, maxR));

		if (doAbsoluteShiftFilter)
			fsr.addFilter(new FilteredStitchingResults.AbsoluteShiftFilter(maxShift));

		if (doMagnitudeFilter)
			fsr.addFilter(new FilteredStitchingResults.ShiftMagnitudeFilter(maxMag));

		if (onlySelectedLinks)
			fsr.applyToWrappedSubset(selectedPairs);
		else
			fsr.applyToWrappedAll();

		return true;
	}

	public SimpleRemoveLinkPopup()
	{
		super("Filter Links by parameters ...");
		this.addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				if (!SpimData2.class.isInstance( panel.getSpimData() ) )
				{
					IOFunctions.println(new Date( System.currentTimeMillis() ) + "ERROR: expected SpimData2, but got " + panel.getSpimData().getClass().getSimpleName());
					return;
				}

				final DemoLinkOverlay demoOverlay;

				if (! StitchingExplorerPanel.class.isInstance( panel ) )
					demoOverlay = null;
				else
					demoOverlay = ( ( StitchingExplorerPanel< ? > ) panel ).getDemoLinkOverlay();

				SpimData2 data = (SpimData2) panel.getSpimData();
				List< List< ViewId > > selected = ((GroupedRowWindow)panel).selectedRowsViewIdGroups();
				selected.forEach( g -> SpimData2.filterMissingViews( data, g ) );
				filterPairwiseShifts(data, true, selected, demoOverlay );
			}
		} );
	}

	
}
