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

import java.util.Arrays;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;

import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;

public class SimpleSubMenu extends JMenu implements ExplorerWindowSetable
{

	private static final long serialVersionUID = 1L;
	private final List<JMenuItem> children;
	
	public SimpleSubMenu(final String title, final JMenuItem ...children)
	{
		super(title);
		this.children = Arrays.asList( children );
		this.children.forEach( c -> this.add( c ) );
	}

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		children.forEach( c -> {
			if (ExplorerWindowSetable.class.isInstance( c ))
				((ExplorerWindowSetable)c).setExplorerWindow( panel );
		} );
		return this;
	}

}
