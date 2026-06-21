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
package net.imglib2.algorithm.phasecorrelation.deprecated;

import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.RealLocalizable;
import net.imglib2.RealRandomAccess;
import net.imglib2.Sampler;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;

/**
 * 
 * @author preibisch
 * 
 * @deprecated
 */
public class BlendingRealRandomAccess implements RealRandomAccess< FloatType >
{
	final Interval interval;
	final int[] min, dimMinus1;
	final float[] l, border, blending;
	final int n;
	final FloatType v;

	// static lookup table for the blending function
	final static private double[] lookUp;
	private static final int indexFor( final double d ) { return (int)Math.round( d * 1000.0 ); }

	static
	{
		lookUp = new double[ 1001 ];

		for ( double d = 0; d <= 1.0001; d = d + 0.001 )
			lookUp[ indexFor( d ) ] = ( Math.cos( ( 1 - d ) * Math.PI ) + 1 ) / 2;
	}

	/**
	 * RealRandomAccess that computes a blending function for a certain {@link Interval}
	 * 
	 * @param interval - the interval it is defined on (return zero outside of it)
	 * @param border - how many pixels to skip before starting blending (on each side of each dimension)
	 * @param blending - how many pixels to compute the blending function on (on each side of each dimension)
	 */
	public BlendingRealRandomAccess(
			final Interval interval,
			final float[] border,
			final float[] blending )
	{
		this.interval = interval;
		this.n = interval.numDimensions();
		this.l = new float[ n ];
		this.border = border;
		this.blending = blending;
		this.v = new FloatType();
		
		this.min = new int[ n ];
		this.dimMinus1 = new int[ n ];
		
		for ( int d = 0; d < n; ++d )
		{
			this.min[ d ] = (int)interval.min( d );
			this.dimMinus1[ d ] = (int)interval.max( d ) - min[ d ];
		}
	}
	
	@Override
	public FloatType get()
	{
		v.set( computeWeight( l, min, dimMinus1, border, blending, n ) );
		return v;
	}

	final private static float computeWeight(
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

	@Override
	public void localize( final float[] position )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] = l[ d ];
	}

	@Override
	public void localize( final double[] position )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] = l[ d ];
	}

	@Override
	public float getFloatPosition( final int d ){ return l[ d ]; }

	@Override
	public double getDoublePosition( final int d ) { return l[ d ]; }

	@Override
	public int numDimensions() { return n; }

	@Override
	public void move( final float distance, final int d ) { l[ d ] += distance; }

	@Override
	public void move( final double distance, final int d ) { l[ d ] += distance; }

	@Override
	public void move( final RealLocalizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += localizable.getFloatPosition( d );
	}

	@Override
	public void move( final float[] distance )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += distance[ d ];
	}

	@Override
	public void move( final double[] distance )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += distance[ d ];
	}

	@Override
	public void setPosition( final RealLocalizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = localizable.getFloatPosition( d );
	}

	@Override
	public void setPosition( final float[] position )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = position[ d ];
	}

	@Override
	public void setPosition( final double[] position )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] =(float)position[ d ];
	}

	@Override
	public void setPosition( final float position, final int d ) { l[ d ] = position; }

	@Override
	public void setPosition( final double position, final int d ) { l[ d ] = (float)position; }

	@Override
	public void fwd( final int d ) { ++l[ d ]; }

	@Override
	public void bck( final int d ) { --l[ d ]; }

	@Override
	public void move( final int distance, final int d ) { l[ d ] += distance; }

	@Override
	public void move( final long distance, final int d ) { l[ d ] += distance; }

	@Override
	public void move( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += localizable.getFloatPosition( d );
	}

	@Override
	public void move( final int[] distance )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += distance[ d ];
	}

	@Override
	public void move( final long[] distance )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += distance[ d ];
	}

	@Override
	public void setPosition( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = localizable.getFloatPosition( d );
	}

	@Override
	public void setPosition( final int[] position )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = position[ d ];
	}

	@Override
	public void setPosition( final long[] position )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = position[ d ];
	}

	@Override
	public void setPosition( final int position, final int d ) { l[ d ] = position; }

	@Override
	public void setPosition( final long position, final int d ) { l[ d ] = position; }

	@Override
	public RealRandomAccess<FloatType> copy()
	{
		final BlendingRealRandomAccess r = new BlendingRealRandomAccess( interval, border, blending );
		r.setPosition( this );
		return r;
	}

	public static void main( String[] args )
	{
		new ImageJ();
		
		Img< FloatType > img = ArrayImgs.floats( 500, 500 );
		BlendingRealRandomAccess blend = new BlendingRealRandomAccess(
				img,
				new float[]{ 100, 0 },
				new float[]{ 12, 150 } );
		
		Cursor< FloatType > c = img.localizingCursor();
		
		while ( c.hasNext() )
		{
			c.fwd();
			blend.setPosition( c );
			c.get().setReal( blend.get().getRealFloat() );
		}
		
		ImageJFunctions.show( img );
	}
}
