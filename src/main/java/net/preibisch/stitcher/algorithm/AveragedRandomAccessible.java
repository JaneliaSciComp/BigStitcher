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
package net.preibisch.stitcher.algorithm;

import java.util.ArrayList;

import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.Sampler;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;

// maybe hardcode for 2,3 channels AverageRandomAccess2Channels and AverageRandomAccess3Channels
public  class  AveragedRandomAccessible <T extends RealType<T >> implements RandomAccessible< T >
{
	final private int numD;
	final private ArrayList<RandomAccessible< T >> randomAccessibles;
	
	public AveragedRandomAccessible(int numD)
	{
		this.numD = numD;
		this.randomAccessibles = new ArrayList<>();
	}
	
	public void addRAble( RandomAccessible< T > RAble)
	{
		randomAccessibles.add( RAble );
	}
	
	@Override
	public int numDimensions()
	{
		return numD;
	}

	@Override
	public RandomAccess< T > randomAccess()
	{
		return new AverageRandomAccess(numD);
	}

	@Override
	public RandomAccess< T > randomAccess(Interval interval)
	{
		return new AverageRandomAccess(numD);
	}

	@Override
	public T getType()
	{
		return randomAccessibles.isEmpty() ? null : randomAccessibles.get( 0 ).getType();
	}

	class AverageRandomAccess /* extends Point */ implements RandomAccess< T >
	{
		final ArrayList< RandomAccess< T > > RAs;
		final T type;
		
		public AverageRandomAccess(int numD)
		{
			// TODO: this (and methods below) will throw NPE if there are no RAbles
			RAs = new ArrayList<>();
			type = randomAccessibles.get( 0 ).randomAccess().get().createVariable();
			for (final RandomAccessible< T > RAbleI : randomAccessibles)
			{
				RAs.add( RAbleI.randomAccess() );
			}
		}
		
		@Override
		public void fwd( final int dim )
		{
			for (final RandomAccess< T > r : RAs )
				r.fwd( dim );
		}
		
		@Override
		public T get()
		{
			double sum = 0.0;
			int count = 0;
			for (final RandomAccess< T > ra : RAs)
			{
				sum += ra.get().getRealDouble();
				count++;
			}	
			
			type.setReal( sum / count );
			return type;
		}

		@Override
		public RandomAccess< T > copy()
		{
			AverageRandomAccess ra = new AverageRandomAccess(numD);
			ra.setPosition( this );
			return ra;
		}

		@Override
		public void localize(int[] position)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.localize( position );		
		}

		@Override
		public void localize(long[] position)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.localize( position );
		}

		@Override
		public int getIntPosition(int d){ return RAs.get( 0 ).getIntPosition( d ); }
		@Override
		public long getLongPosition(int d) {return RAs.get( 0 ).getLongPosition( d );}

		@Override
		public void localize(float[] position)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.localize( position );
		}

		@Override
		public void localize(double[] position)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.localize( position );
		}

		@Override
		public float getFloatPosition(int d) {return RAs.get( 0 ).getFloatPosition( d ); }

		@Override
		public double getDoublePosition(int d){return RAs.get( 0 ).getDoublePosition( d ); }

		@Override
		public int numDimensions() {return numD;}

		@Override
		public void bck(int d)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.bck(d);
			
		}

		@Override
		public void move(int distance, int d)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.move( distance, d );
		}

		@Override
		public void move(long distance, int d)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.move( distance, d );
		}

		@Override
		public void move(Localizable localizable)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.move( localizable);
		}

		@Override
		public void move(int[] distance)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.move( distance);
		}

		@Override
		public void move(long[] distance)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.move( distance );
		}

		@Override
		public void setPosition(Localizable localizable)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.setPosition( localizable );
		}

		@Override
		public void setPosition(int[] position)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.setPosition( position );
		}

		@Override
		public void setPosition(long[] position)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.setPosition( position );
		}

		@Override
		public void setPosition(int position, int d)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.setPosition( position , d);
		}

		@Override
		public void setPosition(long position, int d)
		{
			for (final RandomAccess< T > ra : RAs)
				ra.setPosition( position , d);			
		}
		
	}

	

}
