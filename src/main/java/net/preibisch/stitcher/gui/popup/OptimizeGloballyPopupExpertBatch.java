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

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;
import net.preibisch.stitcher.algorithm.globalopt.ExecuteGlobalOpt;

public class OptimizeGloballyPopupExpertBatch extends JMenuItem
		implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 1L;

	private ExplorerWindow< ? > panel;
	private boolean expertMode;

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	public OptimizeGloballyPopupExpertBatch( boolean expertMode )
	{
		super( expertMode ? "Expert Mode" : "Simple Mode" );
		this.expertMode = expertMode;
		this.addActionListener( new MyActionListener() );
	}

	private class MyActionListener implements ActionListener
	{
		@Override
		public void actionPerformed(ActionEvent e)
		{
			new Thread( new ExecuteGlobalOpt( panel, expertMode ) ).start();
		}
	}
}
