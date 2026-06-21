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

import net.imglib2.RealLocalizable;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform;

public class AffineWarp implements WarpFunction
{
	final int n;
	final int m;

	public AffineWarp( final int numDimensions )
	{
		n = numDimensions;
		m = n * ( n + 1 );
	}

	@Override
	public int numParameters()
	{
		return m;
	}

	@Override
	public double partial( final RealLocalizable pos, final int d, final int param )
	{
		final int i = param / ( n + 1 );
		if ( i != d )
			return 0.0;
		final int j = param - i * ( n + 1 );
		return j == n ? 1.0 : pos.getDoublePosition( j );
	}

	@Override
	public AffineGet getAffine( final double[] p )
	{
		final AffineTransform t = new AffineTransform( n );
		int i = 0;
		for ( int r = 0; r < n; ++r )
			for ( int c = 0; c < n + 1; ++c )
				t.set( ( c==r ? 1 : 0 ) + p[ i++ ], r, c );
		return t;
	}
}
