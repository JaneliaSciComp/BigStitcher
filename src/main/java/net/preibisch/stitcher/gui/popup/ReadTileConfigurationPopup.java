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

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuItem;

import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.stitcher.gui.ReadTileConfigurationPanel;

public class ReadTileConfigurationPopup extends JMenuItem implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 8420300257587465114L;
	private ExplorerWindow< ? > panel;

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	public ReadTileConfigurationPopup()
	{
		super( "Read Locations From File" );

		addActionListener( new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				new Thread(new Runnable()
				{
					@Override
					public void run()
					{
						if ( panel == null )
						{
							IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
							return;
						}

						JFrame frame = new JFrame( "Read Locations from File" );

						final ReadTileConfigurationPanel tcPanel = new ReadTileConfigurationPanel( panel.getSpimData(), panel.bdvPopup(), frame );
						frame.add( tcPanel, BorderLayout.CENTER );
						frame.setSize( tcPanel.getPreferredSize() );

						frame.addWindowListener( new WindowAdapter()
						{
							@Override
							public void windowClosing( WindowEvent evt )
							{
								tcPanel.quit();
							}
						} );

						frame.pack();
						frame.setVisible( true );
						frame.requestFocus();
					}
				}).start();
			}
		} );
	}

}
