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
package net.preibisch.stitcher.algorithm.fastfusion;

public class BlendingTools
{
	// static lookup table for the blending function
	final static private double[] lookUp;
	private static int indexFor( final double d ) { return (int)Math.round( d * 1000.0 ); }

	static
	{
		lookUp = new double[ 1001 ];
		for ( double d = 0; d <= 1.0001; d = d + 0.001 )
			lookUp[ indexFor( d ) ] = ( Math.cos( ( 1 - d ) * Math.PI ) + 1 ) / 2;
	}

	public static float computeWeight(
			final float[] location,
			final int[] min, 
			final int[] dimMinus1,
			final float[] border, 
			final float[] blending,
			final int n )
	{
		// compute multiplicative distance to the respective borders [0...1]
		float minDistance = 1;

		for ( int d = 0; d < n; ++d )
		{
			// the position in the image relative to the boundaries and the border
			final float l = ( location[ d ] - min[ d ] );

			// the distance to the border that is closer
			final float dist = Math.max( 0, Math.min( l - border[ d ], dimMinus1[ d ] - l - border[ d ] ) );

			// if this is 0, the total result will be 0, independent of the number of dimensions
			if ( dist == 0 )
				return 0;

			final float relDist = dist / blending[ d ];

			if ( relDist < 1 )
				minDistance *= lookUp[ indexFor( relDist ) ]; //( Math.cos( ( 1 - relDist ) * Math.PI ) + 1 ) / 2;
		}

		return minDistance;
	}

}
