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
package net.preibisch.stitcher.arrangement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.preibisch.stitcher.algorithm.TransformTools;

public class SkewImages
{
	public static List<AffineTransform3D> getAccumulativeSkewTransform(final List<AffineTransform3D> currentTransforms, final int skewDirection, final int alongAxis, final double angle)
	{
		final List<AffineTransform3D> res = new ArrayList<>();

		for (final AffineTransform3D currentTransform : currentTransforms)
		{
			final Pair< AffineGet, AffineGet > decomp = TransformTools.decomposeIntoAffineAndTranslation( currentTransform );

			final AffineTransform3D skew = new AffineTransform3D();
			skew.set( Math.tan( angle ), skewDirection, alongAxis );
			// transformation order should be: 
			// move to origin -> inverse affine to axis-aligned pixels -> skew -> re-apply affine -> re-apply translation
			skew.concatenate( decomp.getA().inverse() );
			skew.concatenate( decomp.getB().inverse() );
			skew.preConcatenate( decomp.getA() );
			skew.preConcatenate( decomp.getB() );

			res.add( skew );
		}

		return res;
	}
	
	public static void applySkewToData(final ViewRegistrations vrs, final Collection<ViewId> vids, final int skewDirection, final int alongAxis, final double angle)
	{
		applySkewToData(vrs, new HashMap<>(), vids, skewDirection, alongAxis, angle);
	}

	public static void applySkewToData(final ViewRegistrations vrs, final Map<ViewId, Dimensions> dimensions, final Collection<ViewId> vids, final int skewDirection, final int alongAxis, final double angle)
	{
		// get current registrations
		final List<AffineTransform3D> currentTransforms = new ArrayList<>();
		for (final ViewId vid : vids )
		{
			final ViewRegistration vr = vrs.getViewRegistration( vid );
			final ViewTransform calibration = vr.getTransformList().get( vr.getTransformList().size() - 1 );
			vr.updateModel();
			if (dimensions.containsKey( vid ))
			{
				final AffineTransform3D currentTransformViewCenter = new AffineTransform3D();
				currentTransformViewCenter.set( dimensions.get( vid ).dimension( 0 )/2.0f, 0, 3 );
				currentTransformViewCenter.set( dimensions.get( vid ).dimension( 1 )/2.0f, 1, 3 );
				currentTransformViewCenter.set( dimensions.get( vid ).dimension( 2 )/2.0f, 2, 3 );
				currentTransformViewCenter.preConcatenate( vr.getModel() );
				currentTransforms.add( currentTransformViewCenter.preConcatenate( calibration.asAffine3D().inverse() ) );
			}
			else
				currentTransforms.add( vr.getModel().copy().preConcatenate( calibration.asAffine3D().inverse() ) );
		}

		// append skew transform
		final List< AffineTransform3D > skewTransform = getAccumulativeSkewTransform( currentTransforms, skewDirection, alongAxis, angle );
		final AtomicInteger i = new AtomicInteger();
		for (final ViewId vid : vids )
		{
			final ViewRegistration vr = vrs.getViewRegistration( vid );
			vr.preconcatenateTransform( new ViewTransformAffine( String.format(
					"Skew by %f degrees in %s along %s axis",
					angle/Math.PI*180,
					Character.toString( (char) (skewDirection + 88) ),
					Character.toString( (char) (alongAxis + 88) )),
				skewTransform.get( i.getAndIncrement() ) ) );
			vr.updateModel();
		}
	}

}
