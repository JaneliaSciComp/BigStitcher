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

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.gui.LinkExplorerPanel;
import net.preibisch.stitcher.gui.StitchingExplorerPanel;
import net.preibisch.stitcher.gui.StitchingResultsSettable;

public class LinkExplorerRemoveLinkPopup extends JMenuItem implements StitchingResultsSettable, ExplorerWindowSetable
{
	private LinkExplorerPanel panel;
	private StitchingResults results;
	private ExplorerWindow< ? > stitchingExplorer;

	public LinkExplorerRemoveLinkPopup(LinkExplorerPanel panel)
	{
		super("Remove Link");
		this.panel = panel;
		this.addActionListener( new MyActionListener());
	}
	
	public class MyActionListener implements ActionListener
	{

			@Override
			public void actionPerformed(ActionEvent e)
			{
				final Pair< Group<ViewId>, Group<ViewId> > pair = panel.getModel().getActiveLinks().get( panel.getTable().getSelectedRow() );
				results.removePairwiseResultForPair( pair );
				((StitchingExplorerPanel< ? >)stitchingExplorer).updateBDVPreviewMode();

				panel.selectedViewDescriptions( new ArrayList<>(((GroupedRowWindow)stitchingExplorer).selectedRowsGroups()) );
				panel.getModel().fireTableDataChanged();
			}

	}

	@Override
	public void setStitchingResults(StitchingResults res)
	{
		results = res;
	}

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.stitchingExplorer = panel;
		return this;
	}

}
