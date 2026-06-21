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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.preibisch.stitcher.algorithm.TransformTools;

public class FlipAxes
{
	public static List<AffineTransform3D> getAccumulativeFlipTransform(final List<AffineTransform3D> currentTransforms, final boolean[] flipAxes)
	{
		final List<AffineTransform3D> res = new ArrayList<>();

		for (final AffineTransform3D currentTransform : currentTransforms)
		{
			final Pair< AffineGet, AffineGet > decomp = TransformTools.decomposeIntoAffineAndTranslation( currentTransform );

			final AffineTransform3D flip = new AffineTransform3D();
			for (int d=0; d<3; d++)
			{
				if (flipAxes[d])
					flip.set( -1, d, d );
			}
			// transformation order should be: 
			// move to origin -> inverse affine to axis-aligned pixels -> flip -> re-apply affine -> re-apply translation
			flip.concatenate( decomp.getA().inverse() );
			flip.concatenate( decomp.getB().inverse() );
			flip.preConcatenate( decomp.getA() );
			flip.preConcatenate( decomp.getB() );

			res.add( flip );
		}

		return res;
	}

	public static void applyFlipToData(final ViewRegistrations vrs, final Collection<ViewId> vids, final boolean[] flipAxes)
	{
		applyFlipToData(vrs, new HashMap<>(), vids, flipAxes);
	}

	public static void applyFlipToData(final ViewRegistrations vrs, final Map<ViewId, Dimensions> dimensions, final Collection<ViewId> vids, final boolean[] flipAxes)
	{
		// get current registrations
		final List<AffineTransform3D> currentTransforms = new ArrayList<>();
		for (final ViewId vid : vids )
		{
			final ViewRegistration vr = vrs.getViewRegistration( vid );
			vr.updateModel();
			if (dimensions.containsKey( vid ))
			{
				final AffineTransform3D currentTransformViewCenter = new AffineTransform3D();
				currentTransformViewCenter.set( dimensions.get( vid ).dimension( 0 )/2.0f, 0, 3 );
				currentTransformViewCenter.set( dimensions.get( vid ).dimension( 1 )/2.0f, 1, 3 );
				currentTransformViewCenter.set( dimensions.get( vid ).dimension( 2 )/2.0f, 2, 3 );
				currentTransformViewCenter.preConcatenate( vr.getModel() );
				currentTransforms.add( currentTransformViewCenter );
			}
			else
				currentTransforms.add( vr.getModel() );
		}

		// append flip transform
		final List< AffineTransform3D > flipTransform = getAccumulativeFlipTransform( currentTransforms, flipAxes );
		final AtomicInteger i = new AtomicInteger();
		for (final ViewId vid : vids )
		{
			final ViewRegistration vr = vrs.getViewRegistration( vid );
			final String flipAxesString = String.join( ", ", IntStream.range( 0, flipAxes.length ).boxed()
					.filter( j -> flipAxes[j] )
					.map( j -> Character.toString( (char) (j + 88) ) )
					.collect( Collectors.toList() )
					); 
			vr.preconcatenateTransform( new ViewTransformAffine( "Flip along " + flipAxesString, flipTransform.get( i.getAndIncrement() ) ) );
			vr.updateModel();
		}
	}

}
