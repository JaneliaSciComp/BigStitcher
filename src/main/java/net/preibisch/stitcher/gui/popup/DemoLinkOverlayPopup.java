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

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import bdv.BigDataViewer;
import net.imglib2.type.numeric.ARGBType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import util.BDVTools;
import net.preibisch.stitcher.gui.overlay.DemoLinkOverlay;

public class DemoLinkOverlayPopup extends JMenuItem implements ExplorerWindowSetable, KeyListener
{
	private static final long serialVersionUID = 1L;

	ExplorerWindow< ? > panel;
	DemoLinkOverlay overlay;
	boolean active = false;
	MyActionListener actionListener;

	public DemoLinkOverlayPopup(DemoLinkOverlay overlay)
	{
		super( "Toggle Demo Link Overlay" );
		
		this.actionListener = new MyActionListener();
		this.addActionListener( this.actionListener );
		this.overlay = overlay;
	}

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	public class MyActionListener implements ActionListener
	{
		final ArrayList< ARGBType > oldColors = new ArrayList<>();

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			active = !active;
			
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			overlay.isActive = active;
			
			BigDataViewer bdv = panel.bdvPopup().getBDV();
			
			if (bdv == null)
			{
				IOFunctions.println( "BigDataViewer is not open. Please start it to access this funtionality." );
				return;
			}
	
			// remove if it is already there
			bdv.getViewer().removeTransformListener( overlay );
			bdv.getViewer().getDisplay().overlays().remove( overlay );

			for ( int i = 0; i < Math.min( oldColors.size(), bdv.getSetupAssignments().getConverterSetups().size() ); ++i )
				bdv.getSetupAssignments().getConverterSetups().get( i ).setColor( oldColors.get( i ) );

			if (active)
			{
				bdv.getViewer().addTransformListener( overlay );
				bdv.getViewer().getDisplay().addOverlayRenderer( overlay );

				oldColors.clear();
				for ( int i = 0; i < bdv.getSetupAssignments().getConverterSetups().size(); ++i )
					oldColors.add( bdv.getSetupAssignments().getConverterSetups().get( i ).getColor() );

				colorSources( bdv );
			}

			bdv.getViewer().requestRepaint();
		}
	}

	public void colorSources( final BigDataViewer bdv )
	{
		if ( bdv != null )
			BDVTools.sameColorSources( bdv.getSetupAssignments().getConverterSetups(), 64, 64, 96, 255 );
	}

	@Override
	public void keyPressed( KeyEvent e )
	{
		if ( e.getKeyChar() == 'l' || e.getKeyChar() == 'L' )
		{
			this.actionListener.actionPerformed( new ActionEvent( panel, 0, "key pressed" ) );
		}
	}

	@Override
	public void keyTyped( KeyEvent e ) {}

	@Override
	public void keyReleased( KeyEvent e ) {}
}
