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
package net.preibisch.stitcher.algorithm.lucaskanade;

import net.imglib2.Point;
import net.imglib2.RealLocalizable;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.util.Util;

/**
 * 
 * 3D Rigid Transformation parameterized by a quaternion rotation p0,...,p3 and
 * translation p4,...,p6
 * 
 * Quaternion derivatives and to Mat according to
 * https://en.wikipedia.org/wiki/Quaternions_and_spatial_rotation
 * http://home.ewha.ac.kr/~bulee/quaternion.pdf
 * 
 * @author David Hoerl
 *
 */
public class RigidWarp3D implements WarpFunction
{

	// value of dx/dp at (1,0,0,0,0,0,0) for (d,index(p)):
	// 1 -> x, 2 -> y, ..
	// -1 -> -x, -2 -> -y, ..
	// 0 -> 0
	static int[][] lut = new int[][] {
		new int[] { 1, 0, 3, -2 },
		new int[] { 2, -3, 0, 1 },
		new int[] { 3, 2, -1, 0 }
	};

	@Override
	public int numParameters()
	{
		return 7;
	}

	@Override
	public double partial(RealLocalizable pos, int d, int param)
	{
		// we evaluate at p1,...,p3 = (1,0,0,0) (identity as quat)

		// translational part of Jacobian = I
		if ( param > 3 )
			return ( param - 4 ) == d ? 1.0 : 0.0;

		final int c = lut[d][param];

		// set Quat derivatives according to LUT above (*2)
		return c == 0 ? 0 : ( c < 0 ? -2 * pos.getDoublePosition( -c - 1 ) : 2 * pos.getDoublePosition( c - 1 ) );
	}

	@Override
	public AffineGet getAffine(double[] pIn)
	{
		final AffineTransform res = new AffineTransform( 3 );
		double[] p = pIn.clone();

		// we calculated it for p0' = 1 + p0
		p[0] += 1;

		// normalize the quat
		double ssum = 0;
		for ( int pi = 0; pi < 4; pi++ )
			ssum += p[pi] * p[pi];
		ssum = Math.sqrt( ssum );
		for ( int pi = 0; pi < 4; pi++ )
			p[pi] /= ssum;

		// Quat to Mat
		res.set( p[0] * p[0] + p[1] * p[1] - p[2] * p[2] - p[3] * p[3], 0, 0 );
		res.set( -2 * p[0] * p[3] + 2 * p[1] * p[2], 0, 1 );
		res.set( 2 * p[0] * p[2] + 2 * p[1] * p[3], 0, 2 );

		res.set( 2 * p[0] * p[3] + 2 * p[1] * p[2], 1, 0 );
		res.set( p[0] * p[0] - p[1] * p[1] + p[2] * p[2] - p[3] * p[3], 1, 1 );
		res.set( -2 * p[0] * p[1] + 2 * p[2] * p[3], 1, 2 );

		res.set( -2 * p[0] * p[2] + 2 * p[1] * p[3], 2, 0 );
		res.set( 2 * p[0] * p[1] + 2 * p[1] * p[3], 2, 1 );
		res.set( p[0] * p[0] - p[1] * p[1] - p[2] * p[2] + p[3] * p[3], 2, 2 );

		// translational part
		res.set( p[4], 0, 3 );
		res.set( p[5], 1, 3 );
		res.set( p[6], 2, 3 );

		return res;
	}

	public static void main(String[] args)
	{
		RigidWarp3D warp = new RigidWarp3D();
		AffineGet affine = warp
				.getAffine( new double[] { Math.cos( Math.PI / 4 ) - 1, 0, 0, Math.sin( Math.PI / 4 ), 2, 2, 2 } );
		System.out.println( Util.printCoordinates( affine.getRowPackedCopy() ) );

		for ( int d = 0; d < 3; d++ )
			for ( int p = 0; p < warp.numParameters(); p++ )
				System.out.println( warp.partial( new Point( 1, 2, 3 ), d, p ) );
	}

}
