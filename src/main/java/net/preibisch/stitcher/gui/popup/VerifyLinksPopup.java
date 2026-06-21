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

import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.gui.overlay.DemoLinkOverlay;

public class VerifyLinksPopup extends JMenu implements ExplorerWindowSetable
{
	private ExplorerWindow< ? > panel;
	private TogglePreviewPopup interactiveExplorer;
	private SimpleRemoveLinkPopup parameterBasedRemoval;
	private JMenu removeAllPopup;
	private JMenuItem removeAll;
	private JMenuItem removeAllSelected;

	public VerifyLinksPopup( final DemoLinkOverlay overlap )
	{
		super("Verify/Filter Pairwise Links");
		this.interactiveExplorer = new TogglePreviewPopup();
		this.parameterBasedRemoval = new SimpleRemoveLinkPopup();

		this.add( interactiveExplorer );
		this.add( parameterBasedRemoval );
		removeAllPopup = new JMenu( "Remove Links" );
		removeAll = new JMenuItem( "Remove All" );
		removeAllSelected = new JMenuItem( "Remove For Selected Views" );

		removeAllPopup.add( removeAll );
		removeAllPopup.add( removeAllSelected );
		this.add( removeAllPopup );

		// remove all pairwise results
		removeAll.addActionListener( a -> {
			if (SpimData2.class.isInstance( panel.getSpimData() ))
				((SpimData2)panel.getSpimData()).getStitchingResults().getPairwiseResults().clear();

			if ( overlap != null )
			{
				overlap.getFilteredResults().clear();
				overlap.getInconsistentResults().clear();

				panel.bdvPopup().updateBDV();
			}
		});

		// remove pairwise results for selected groups
		removeAllSelected.addActionListener( a -> {
			if (SpimData2.class.isInstance( panel.getSpimData() ))
			{
				StitchingResults sr = ((SpimData2)panel.getSpimData()).getStitchingResults();
				List< List< ViewId > > selected = ((GroupedRowWindow)panel).selectedRowsViewIdGroups();
				ArrayList< Pair< Group< ViewId >, Group< ViewId > > > pairs = new ArrayList<>(sr.getPairwiseResults().keySet());
				for (int i = 0; i<selected.size(); i++)
				{
					Group< ViewId > grp = new Group<>(selected.get(i));
					for (Pair<Group<ViewId>, Group<ViewId>> p : pairs)
						if (p.getA().equals( grp ) || p.getB().equals( grp ))
							sr.removePairwiseResultForPair( p );

					if ( overlap != null )
					{
						for ( int j = overlap.getFilteredResults().size() - 1; j >= 0; --j )
						{
							final Pair<Group<ViewId>, Group<ViewId>> p = overlap.getFilteredResults().get( j );

							if (p.getA().equals( grp ) || p.getB().equals( grp ))
								overlap.getFilteredResults().remove( j );
						}

						for ( int j = overlap.getInconsistentResults().size() - 1; j >= 0; --j )
						{
							final Pair<Group<ViewId>, Group<ViewId>> p = overlap.getInconsistentResults().get( j );

							if (p.getA().equals( grp ) || p.getB().equals( grp ))
								overlap.getInconsistentResults().remove( j );
						}

						panel.bdvPopup().updateBDV();
					}
				}
			}
		});

	}

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		interactiveExplorer.setExplorerWindow( panel );
		parameterBasedRemoval.setExplorerWindow( panel );
		return this;
	}

}
