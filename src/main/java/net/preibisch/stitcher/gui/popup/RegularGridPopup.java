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

import javax.swing.JFrame;
import javax.swing.JMenuItem;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.stitcher.gui.PreviewRegularGridPanel;

public class RegularGridPopup extends JMenuItem implements ExplorerWindowSetable {
	
	private static final long serialVersionUID = 5234649267634013390L;
	public static boolean showWarning = true;

	ExplorerWindow< ? > panel;
	PreviewRegularGridPanel< ? > regularGridPanel;

	public RegularGridPopup() 
	{
		super( "Move Tiles to Regular Grid ..." );
		this.addActionListener( new MyActionListener() );
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
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

			/*
			BigDataViewer bdv = panel.bdvPopup().getBDV();
			
			if (bdv == null)
			{
				IOFunctions.println( "BigDataViewer is not open. Please start it to access this funtionality." );
				return;
			}
			*/

			regularGridPanel = new PreviewRegularGridPanel<>( panel );

			JFrame frame = new JFrame( "Regular Grid Options" );
			frame.add( regularGridPanel, BorderLayout.CENTER );
			frame.setSize( regularGridPanel.getPreferredSize() );

			frame.addWindowListener( new WindowAdapter()
			{
				@Override
				public void windowClosing( WindowEvent evt )
				{
					regularGridPanel.quit();
				}
			} );

			
			frame.pack();
			frame.setVisible( true );
			frame.requestFocus();
			
			/*
			for (int i = 0; i < bdv.getViewer().getVisibilityAndGrouping().numSources(); ++i)
			{
				Integer tpId = bdv.getViewer().getState().getCurrentTimepoint();
				SourceState<?> s = bdv.getViewer().getVisibilityAndGrouping().getSources().get( i );
				
				// get manual transform
				AffineTransform3D tAffine = new AffineTransform3D();
				((TransformedSource< ? >)s.getSpimSource()).getFixedTransform( tAffine );
				
				// get old transform
				ViewRegistration vr = panel.getSpimData().getViewRegistrations().getViewRegistration( new ViewId(tpId, i ));
				AffineGet old = vr.getTransformList().get( 1 ).asAffine3D();
				
				// update transform in ViewRegistrations
				AffineTransform3D newTransform = new AffineTransform3D();
				newTransform.set( old.get( 0, 3 ) + tAffine.get( 0, 3 ), 0, 3 );
				newTransform.set( old.get( 1, 3 ) + tAffine.get( 1, 3 ), 1, 3 );
				newTransform.set( old.get( 2, 3 ) + tAffine.get( 2, 3 ), 2, 3 );
				
				ViewTransform newVt = new ViewTransformAffine( "Translation", newTransform );				
				vr.getTransformList().set( 1, newVt );
				vr.updateModel();
				
				// reset manual transform
				((TransformedSource< ? >)s.getSpimSource()).setFixedTransform( new AffineTransform3D() );
				bdv.getViewer().requestRepaint();
			}
			
			panel.bdvPopup().updateBDV();			
			
			*/
		}
	}

}
