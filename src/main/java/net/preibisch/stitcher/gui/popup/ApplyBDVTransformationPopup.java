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

import bdv.AbstractSpimSource;
import bdv.BigDataViewer;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerState;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import javax.swing.JMenuItem;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.TimePoint;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.ExplorerWindowSetable;

public class ApplyBDVTransformationPopup extends JMenuItem implements ExplorerWindowSetable {

	private static final long serialVersionUID = 5234649267634013390L;
	public static boolean showWarning = true;

	ExplorerWindow< ? > panel;

	public ApplyBDVTransformationPopup()
	{
		super( "Apply BDV Transformation to Data" );
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

			BigDataViewer bdv = panel.bdvPopup().getBDV();

			if (bdv == null)
			{
				IOFunctions.println( "BigDataViewer is not open. Please start it to access this functionality." );
				return;
			}

			final ViewerState state = bdv.getViewer().state().snapshot();

			final List< TimePoint > timePointsOrdered = panel.getSpimData().getSequenceDescription().getTimePoints().getTimePointsOrdered();
			final int tpId = timePointsOrdered.get( state.getCurrentTimepoint() ).getId();

			final ViewRegistrations viewRegistrations = panel.getSpimData().getViewRegistrations();

			for ( SourceAndConverter< ? > source : state.getSources() )
			{
				try
				{
					applyManualTransform( source, viewRegistrations, tpId );
				}
				catch ( IllegalArgumentException exception )
				{
					// source is not AbstractSpimSource wrapped a TransformedSource.
					// ==> Just ignore this source.
				}
			}

			panel.bdvPopup().updateBDV();
		}
	}

	/**
	 * Apply manual transform permanently, by adding it to {@link ViewRegistration}.
	 * <p>
	 * This method assume that {@code source} is a {@link TransformedSource}
	 * that wraps an {@link AbstractSpimSource}.
	 *
	 * @param source source for which to apply the manual transform
	 * @param viewRegistrations containing the {@code ViewRegistration} for source
	 * @param timepointId id of the timepoint where transformations should be applied
	 *
	 * @throws IllegalArgumentException id {@code source} is not a {@link TransformedSource} that wraps an {@link AbstractSpimSource}.
	 */
	// TODO (TP): shouldn't the transformation be applied to all timepoints?
	private static void applyManualTransform( final SourceAndConverter< ? > source, final ViewRegistrations viewRegistrations, final int timepointId ) throws IllegalArgumentException
	{
		if ( !( source.getSpimSource() instanceof TransformedSource ) )
			throw new IllegalArgumentException( "expected TransformedSource, not " + source.getSpimSource() );
		final TransformedSource< ? > tfs = ( TransformedSource< ? > ) source.getSpimSource();

		if ( !( tfs.getWrappedSource() instanceof AbstractSpimSource ) )
			throw new IllegalArgumentException( "expected AbstractSpimSource, got " + tfs.getWrappedSource() );
		final AbstractSpimSource< ? > s = ( AbstractSpimSource< ? > ) tfs.getWrappedSource();

		// TODO (TP): The following makes some very specific assumptions of the TransformList. It looks extremely fragile. What was the intended purpose?

		// get manual transform
		AffineTransform3D tAffine = new AffineTransform3D();
		tfs.getFixedTransform( tAffine );

		// get old transform
		ViewRegistration vr = viewRegistrations.getViewRegistration( timepointId, s.getSetupId() );
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
		tfs.setFixedTransform( new AffineTransform3D() );
	}
}
