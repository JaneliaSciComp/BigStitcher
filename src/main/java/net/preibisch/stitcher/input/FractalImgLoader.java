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
package net.preibisch.stitcher.input;

import java.util.List;

import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.stitcher.algorithm.AveragedRandomAccessible;

public class FractalImgLoader implements ImgLoader
{
	final List<Interval> intervals;
	final VoxelDimensions vd0;
	final AveragedRandomAccessible< LongType > fractalsRA;

	public FractalImgLoader( final List<Interval> intervals, final VoxelDimensions vd0, final AveragedRandomAccessible< LongType > fractalsRA )
	{
		this.intervals = intervals;
		this.vd0 = vd0;
		this.fractalsRA = fractalsRA;
	}

	public RandomAccessibleInterval< LongType > getImageAtInterval(Interval interval){
		return  Views.zeroMin( Views.interval( fractalsRA, interval) );
		/*
		// we want a Img here, so that we can downsaple later
		Img<LongType> resImg = new ArrayImgFactory<LongType>().create( raiT, new LongType() );
		
		ops.copy().rai( resImg, raiT );
		//ImgLib2Util.copyRealImage(raiT, resImg) ;
		return resImg;*/
	}

	@Override
	public SetupImgLoader< ? > getSetupImgLoader( final int setupId )
	{
		return new SetupImgLoader< LongType >()
		{

			@Override
			public RandomAccessibleInterval< LongType > getImage(int timepointId, ImgLoaderHint... hints)
			{
				return getImageAtInterval( intervals.get( setupId / 2 ));
			}

			@Override
			public LongType getImageType() {return new LongType();}
			

			@Override
			public RandomAccessibleInterval< FloatType > getFloatImage(int timepointId, boolean normalize,
					ImgLoaderHint... hints)
			{
				return Converters.convert( getImage( timepointId, hints ), new Converter< LongType, FloatType >()
				{
					@Override
					public void convert(LongType input, FloatType output){output.setReal( input.getRealDouble() );}
				}, new FloatType() );
				
			}

			@Override
			public Dimensions getImageSize(int timepointId)
			{
				return intervals.get( 0 );
			}

			@Override
			public VoxelDimensions getVoxelSize(int timepointId)
			{
				return vd0;
			}
			
		};
	}
}
