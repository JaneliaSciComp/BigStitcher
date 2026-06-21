/*
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

package net.preibisch.stitcher.input;

import net.imglib2.RealInterval;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.type.numeric.complex.ComplexDoubleType;
import net.imglib2.type.numeric.integer.LongType;

/**
 * A RealRandomAccess that procedurally generates values (iteration count)
 * for the mandelbrot set.
 *
 * @author Stephan Saalfeld (saalfeld@mpi-cbg.de)
 */
public class JuliaRealRandomAccessible implements RealRandomAccessible< LongType >
{
	final protected ComplexDoubleType c;
	long maxIterations;
	double maxAmplitude;
	final int numDismensions;

	public JuliaRealRandomAccessible()
	{
		this(new ComplexDoubleType(),50,4096,3);
	}

	public JuliaRealRandomAccessible(
			final ComplexDoubleType c,
			final int maxIterations,
			final int maxAmplitude,
			final int numDimensions)
	{
		this.c = c;
		this.maxIterations = maxIterations;
		this.maxAmplitude = maxAmplitude;
		this.numDismensions = numDimensions;
	}

	public void setC( final ComplexDoubleType c )
	{
		this.c.set( c );
	}

	public void setC( final double r, final double i )
	{
		c.set( r, i );
	}

	public void setMaxIterations( final long maxIterations )
	{
		this.maxIterations = maxIterations;
	}

	public void setMaxAmplitude( final double maxAmplitude )
	{
		this.maxAmplitude = maxAmplitude;
	}

	public class JuliaRealRandomAccess extends RealPoint implements RealRandomAccess< LongType >
	{
		final protected ComplexDoubleType a;
		final protected LongType t;

		public JuliaRealRandomAccess()
		{
			super( numDismensions );
			a = new ComplexDoubleType();
			t = new LongType();
		}

		final private long julia( final double x, final double y )
		{
			long i = 0;
			double v = 0;
			a.set( x, y );
			while ( i < maxIterations && v < 4096 )
			{
				a.mul( a );
				a.add( c );
				v = a.getPowerDouble();
				++i;
			}
			long ret =  i < 0 ? 0 : i > 255 ? 255 : i;
			
			// quick'n dirty noise
			return ret + (long) (Math.random() * 5);
		}

		@Override
		public LongType get()
		{
			t.set( julia( position[ 0 ], position[ 1 ] ) );
			return t;
		}

		@Override
		public JuliaRealRandomAccess copyRealRandomAccess()
		{
			return copy();
		}

		@Override
		public JuliaRealRandomAccess copy()
		{
			final JuliaRealRandomAccess copy = new JuliaRealRandomAccess();
			copy.setPosition( this );
			return copy;
		}
	}

	@Override
	public int numDimensions()
	{
		return numDismensions;
	}

	@Override
	public JuliaRealRandomAccess realRandomAccess()
	{
		return new JuliaRealRandomAccess();
	}

	@Override
	public JuliaRealRandomAccess realRandomAccess( final RealInterval interval )
	{
		return realRandomAccess();
	}

	@Override
	public LongType getType()
	{
		return new LongType();
	}
}
