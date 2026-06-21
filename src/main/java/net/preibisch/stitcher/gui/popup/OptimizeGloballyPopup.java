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

import javax.swing.JComponent;
import javax.swing.JMenu;

import mpicbg.spim.data.registration.ViewRegistration;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;

public class OptimizeGloballyPopup extends JMenu implements ExplorerWindowSetable
{

	/**
	 * 
	 */
	private static final long serialVersionUID = -8153572686300701480L;

	public final OptimizeGloballyPopupExpertBatch simpleOptimize;
	public final OptimizeGloballyPopupExpertBatch expertOptimize;
	private ExplorerWindow< ? > panel;

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;

		simpleOptimize.setExplorerWindow( panel );
		expertOptimize.setExplorerWindow( panel );
		return this;
	}

	public OptimizeGloballyPopup()
	{
		super( "Optimize Globally And Apply Shift" );

		this.simpleOptimize = new OptimizeGloballyPopupExpertBatch( false );
		this.expertOptimize = new OptimizeGloballyPopupExpertBatch( true );

		this.add( simpleOptimize );
		this.add( expertOptimize );
	}

	public static AffineTransform3D getAccumulativeTransformForRawDataTransform(ViewRegistration viewRegistration,
			AffineGet rawTransform)
	{
		final AffineTransform3D vrModel = viewRegistration.getModel();
		final AffineTransform3D result = vrModel.inverse().copy();
		result.preConcatenate( rawTransform ).preConcatenate( vrModel );
		return result;
	}

}
