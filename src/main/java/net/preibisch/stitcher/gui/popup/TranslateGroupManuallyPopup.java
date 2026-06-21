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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.ListSelectionModel;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ApplyTransformationPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.stitcher.gui.TranslateGroupManuallyPanel;

public class TranslateGroupManuallyPopup extends JMenuItem implements ExplorerWindowSetable
{

	ExplorerWindow< ? > panel;

	public TranslateGroupManuallyPopup()
	{
		super( "Manually translate Views" );
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

			if (!SpimData2.class.isInstance( panel.getSpimData() ))
			{
				IOFunctions.println( "Current dataset is not SpimData2, cannot open " + this.getClass().getSimpleName() );
				return;
			}

			final List< ViewId > viewIds = ApplyTransformationPopup.getSelectedViews( panel );

			final JFrame theFrame = new JFrame( "Move Views" );			
			TranslateGroupManuallyPanel tgmp = new TranslateGroupManuallyPanel( (SpimData2) panel.getSpimData(), viewIds, panel.bdvPopup(), theFrame);

			((FilteredAndGroupedExplorerPanel) panel).addListener( tgmp );

			// re-select everything
			ListSelectionModel lsm = ((FilteredAndGroupedExplorerPanel< ? >) panel).table.getSelectionModel();
			reSelect( lsm );

			theFrame.add( tgmp );
			theFrame.pack();
			theFrame.setVisible( true );

			theFrame.addWindowListener( new WindowAdapter() 
			{
				@Override
				public void windowClosing(WindowEvent e)
				{
					System.out.println( "closing" );
					((FilteredAndGroupedExplorerPanel< ? >) panel).getListeners().remove( tgmp );

					// re-select everything
					ListSelectionModel lsm = ((FilteredAndGroupedExplorerPanel< ? >) panel).table.getSelectionModel();
					reSelect( lsm );
				}
			});
		}
	}

	public static void reSelect(final ListSelectionModel lsm)
	{
		final int maxSelectionIndex = lsm.getMaxSelectionIndex();
		for (int i = 0; i <= maxSelectionIndex; i++)
			if (lsm.isSelectedIndex( i ))
			{
				lsm.removeSelectionInterval( i, i );
				lsm.addSelectionInterval( i, i );
			}
	}
	
	
	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

}
