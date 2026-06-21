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
package net.imglib2.algorithm.phasecorrelation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;

import org.junit.Test;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

public class PhaseCorrelationTest {

	public static long seed = 4353;

	@Test
	public void testPC() {
		
		// TODO: very large shifts (nearly no overlap) lead to incorrect shift determination (as expected)
		// maybe we can optimize behaviour in this situation
		Img< FloatType > img = ArrayImgs.floats( 200, 200 );
		Random rnd = new Random( seed );
		
		for( FloatType t : img )
			t.set( rnd.nextFloat());
		
		long shiftX = 28;
		long shiftY = 0;
		
		FinalInterval interval1 = new FinalInterval(new long[] {50, 50});
		FinalInterval interval2 = Intervals.translate(interval1, shiftX, 0);
		interval2 = Intervals.translate(interval2, shiftY, 1);

		
		int [] extension = new int[img.numDimensions()];
		Arrays.fill(extension, 10);
		
		RandomAccessibleInterval<FloatType> pcm = PhaseCorrelation2.calculatePCM(Views.interval(img, interval1), Views.zeroMin(Views.interval(img, interval2)), extension, new ArrayImgFactory<FloatType>(), 
				new FloatType(), new ArrayImgFactory<ComplexFloatType>(), new ComplexFloatType(), Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
		
		PhaseCorrelationPeak2 shiftPeak = PhaseCorrelation2.getShift(pcm, Views.interval(img, interval1), Views.zeroMin(Views.interval(img, interval2)), 20, 0, false, Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
		
		long[] expected = new long[]{shiftX, shiftY};
		long[] found = new long[img.numDimensions()];
		
		
		
		shiftPeak.getShift().localize(found);
		
		assertArrayEquals(expected, found);
		
	}

	
	@Test
	public void testPCNegativeShift() {
		
		// TODO: very large shifts (nearly no overlap) lead to incorrect shift determination (as expected)
		// maybe we can optimize behaviour in this situation
		Img< FloatType > img = ArrayImgs.floats( 200, 200 );
		Random rnd = new Random( seed );
		
		for( FloatType t : img )
			t.set( rnd.nextFloat());
		
		long shiftX = -2;
		long shiftY = -2;
		
		FinalInterval interval1 = new FinalInterval(new long[] {50, 50});
		FinalInterval interval2 = Intervals.translate(interval1, shiftX, 0);
		interval2 = Intervals.translate(interval2, shiftY, 1);

		int [] extension = new int[img.numDimensions()];
		Arrays.fill(extension, 10);
		
		RandomAccessibleInterval<FloatType> pcm = PhaseCorrelation2.calculatePCM(
				Views.zeroMin(Views.interval(Views.extendZero( img ), interval1)),
				Views.zeroMin(Views.interval(Views.extendZero( img ), interval2)),
				extension, new ArrayImgFactory<FloatType>(), 
				new FloatType(), new ArrayImgFactory<ComplexFloatType>(), new ComplexFloatType(), Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
		
		PhaseCorrelationPeak2 shiftPeak = PhaseCorrelation2.getShift(pcm,
				Views.zeroMin(Views.interval(Views.extendZero( img ), interval1)),
				Views.zeroMin(Views.interval(Views.extendZero( img ), interval2)),
				20, 0, false, Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
		
		long[] expected = new long[]{shiftX, shiftY};
		long[] found = new long[img.numDimensions()];
		
		
		
		shiftPeak.getShift().localize(found);
		System.out.println( Util.printCoordinates( found ) );
		
		assertArrayEquals(expected, found);
		
	}
	
	@Test
	public void testPCRealShift() {
		
		// TODO: very large shifts (nearly no overlap) lead to incorrect shift determination (as expected)
		// maybe we can optimize behaviour in this situation
		Img< FloatType > img = ArrayImgs.floats( 200, 200 );
		Random rnd = new Random( seed );
		
		for( FloatType t : img )
			t.set( rnd.nextFloat());
		
		double shiftX = -20.9;
		double shiftY = 1.9;
		
		// to test < 0.5 px off
		final double eps = 0.5;
		
		FinalInterval interval2 = new FinalInterval(new long[] {50, 50});
		
		

		AffineRandomAccessible< FloatType, AffineGet > imgTr = RealViews.affine( Views.interpolate( Views.extendZero( img ), new NLinearInterpolatorFactory<>() ), new Translation2D( shiftX, shiftY ));
		IntervalView< FloatType > img2 = Views.interval( Views.raster( imgTr ), interval2);
		
		int [] extension = new int[img.numDimensions()];
		Arrays.fill(extension, 10);
		
		RandomAccessibleInterval<FloatType> pcm = PhaseCorrelation2.calculatePCM(Views.zeroMin(img2), Views.zeroMin(Views.interval(img, interval2)), extension, new ArrayImgFactory<FloatType>(), 
				new FloatType(), new ArrayImgFactory<ComplexFloatType>(), new ComplexFloatType(), Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
		
		PhaseCorrelationPeak2 shiftPeak = PhaseCorrelation2.getShift(pcm, Views.zeroMin(img2), Views.zeroMin(Views.interval(img, interval2)), 20, 0, true, Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
		
		
		double[] expected = new double[]{shiftX, shiftY};
		double[] found = new double[img.numDimensions()];
		
		
		
		
		shiftPeak.getSubpixelShift().localize(found);
		
		System.out.println( Util.printCoordinates( found ) );
		
		
		for (int d = 0; d < expected.length; d++)
			assertTrue( Math.abs( expected[d] - found[d] ) < eps );
		
	}
	
	
}
