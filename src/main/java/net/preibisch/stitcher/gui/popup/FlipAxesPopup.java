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
import java.util.List;
import java.util.Map;

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.stitcher.arrangement.FlipAxes;

public class FlipAxesPopup extends JMenuItem implements ExplorerWindowSetable {

	ExplorerWindow< ? > panel;

	public FlipAxesPopup()
	{
		super( "Flip Axes ..." );
		this.addActionListener( new FlipAxesPopupActionListener() );
	}

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}
	
	private class FlipAxesPopupActionListener implements ActionListener
	{

		@Override
		public void actionPerformed(ActionEvent e)
		{
			final SpimData2 spimData = panel.getSpimData();
			final List< ViewId > views = ((GroupedRowWindow) panel).selectedRowsViewIdGroups().stream().reduce(new ArrayList<>(),  (a,b) -> {a.addAll( b ); return a;} );

			final Map<ViewId, Dimensions> dims = new HashMap<>();
			views.forEach( v -> dims.put( v, spimData.getSequenceDescription().getViewDescriptions() .get( v ).getViewSetup().getSize()) );
			final boolean[] flipAxes = new boolean[3];
			GenericDialog gd = new GenericDialog( "Flip Parameters" );
			gd.addCheckbox( "Flip X", true );
			gd.addCheckbox( "Flip Y", false );
			gd.addCheckbox( "Flip Z", false );

			gd.showDialog();
			if (gd.wasCanceled())
				return;

			flipAxes[0] = gd.getNextBoolean();
			flipAxes[1] = gd.getNextBoolean();
			flipAxes[2] = gd.getNextBoolean();

			FlipAxes.applyFlipToData( spimData.getViewRegistrations(), dims, views, flipAxes );

			panel.updateContent();
			panel.bdvPopup().updateBDV();
		}

	}

}
