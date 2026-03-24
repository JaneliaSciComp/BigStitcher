/*-
 * #%L
 * Multiview stitching of large datasets.
 * %%
 * Copyright (C) 2016 - 2025 Big Stitcher developers.
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.preibisch.mvrecon.process.phasecorrelation.PhaseCorrelation2;
import net.preibisch.mvrecon.process.phasecorrelation.PhaseCorrelationPeak2;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Translation;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.realtransform.TranslationGet;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseStitchingResult;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.algorithm.lucaskanade.Align;
import net.preibisch.stitcher.algorithm.lucaskanade.LucasKanadeParameters;
import net.preibisch.stitcher.input.FractalImgLoader;
import net.preibisch.stitcher.input.FractalSpimDataGenerator;


public class PairwiseStitching
{
	public static boolean debug = false;

	public static <T extends RealType< T >, S extends RealType< S >> Pair< AffineTransform, Double > getShiftLucasKanade(
			final RandomAccessibleInterval< T > input1, final RandomAccessibleInterval< T > input2,
			final TranslationGet t1, final TranslationGet t2, final LucasKanadeParameters params,
			final ExecutorService service)
	{
		// TODO: allow arbitrary pre-registration

		// check if we have singleton dimensions
		boolean[] singletonDims = new boolean[input1.numDimensions()];
		for ( int d = 0; d < input1.numDimensions(); ++d )
			singletonDims[d] = !( input1.dimension( d ) > 1 && input2.dimension( d ) > 1 );
		// TODO: should we consider cases where a dimension is singleton in one
		// image but not the other?

		final RealInterval transformed1 = TransformTools.applyTranslation( input1, t1, singletonDims );
		final RealInterval transformed2 = TransformTools.applyTranslation( input2, t2, singletonDims );

		final RandomAccessibleInterval< T > img1;
		final RandomAccessibleInterval< T > img2;

		// make sure everything is zero-min
		if ( !Views.isZeroMin( input1 ) )
			img1 = Views.dropSingletonDimensions( Views.zeroMin( input1 ) );
		else
			img1 = Views.dropSingletonDimensions( input1 );

		if ( !Views.isZeroMin( input2 ) )
			img2 = Views.dropSingletonDimensions( Views.zeroMin( input2 ) );
		else
			img2 = Views.dropSingletonDimensions( input2 );

		if ( debug )
		{
			System.out.println( "1: " + Util.printInterval( img1 ) );
			System.out.println( "1: " + TransformationTools.printRealInterval( transformed1 ) );
			System.out.println( "2: " + Util.printInterval( img2 ) );
			System.out.println( "2: " + TransformationTools.printRealInterval( transformed2 ) );
		}

		final RealInterval overlap = TransformTools.getOverlap( transformed1, transformed2 );

		if ( debug )
			System.out.println( "O: " + TransformationTools.printRealInterval( overlap ) );

		// not overlapping
		if ( overlap == null )
			return null;

		final RealInterval localOverlap1 = TransformTools.getLocalOverlap( transformed1, overlap );
		final RealInterval localOverlap2 = TransformTools.getLocalOverlap( transformed2, overlap );

		final Interval interval1 = TransformTools.getLocalRasterOverlap( localOverlap1 );
		final Interval interval2 = TransformTools.getLocalRasterOverlap( localOverlap2 );

		if ( debug )
		{
			System.out.println( "1: " + TransformationTools.printRealInterval( localOverlap1 ) );
			System.out.println( "1: " + Util.printInterval( interval1 ) );
			System.out.println( "2: " + TransformationTools.printRealInterval( localOverlap2 ) );
			System.out.println( "2: " + Util.printInterval( interval2 ) );
		}

		// check whether we have 0-sized (or negative sized) or unequal raster
		// overlapIntervals
		// (this should just happen with overlaps < 1px in some dimension)
		// ignore this pair in that case
		for ( int d = 0; d < interval1.numDimensions(); ++d )
		{
			if ( interval1.dimension( d ) <= 0 || interval2.dimension( d ) <= 0
					|| interval1.dimension( d ) != interval2.dimension( d ) )
			{
				System.out.println( "Rastered overlap volume is zero, skipping." );
				return null;
			}
		}

		// do the alignment
		Align< T > lkAlign = new Align< T >( Views.zeroMin( Views.interval( img1, interval1 ) ),
				new ArrayImgFactory< FloatType >( new FloatType() ), params.getWarpFunctionInstance( img1.numDimensions() ) );

		AffineTransform res = lkAlign.align( Views.zeroMin( Views.interval( img2, interval2 ) ), params.maxNumIterations,
				params.minParameterChange, service );

		if (lkAlign.didConverge())
			IOFunctions.println("(" + new Date( System.currentTimeMillis() ) + ") determined transformation:" +  Util.printCoordinates( res.getRowPackedCopy() ) );
		else
			IOFunctions.println("(" + new Date( System.currentTimeMillis() ) + ") registration did not converge" );

		final int nFull =  input1.numDimensions();
		AffineTransform resFull = new AffineTransform( nFull );

		// increase dimensionality of transform if necessary
		int dReducedDims = 0;
		for ( int d = 0; d < nFull; ++d )
		{
			if (! singletonDims[d] )
			{
				int dReducedDimsCol = 0;
				for ( int dCol = 0; dCol < nFull + 1; ++dCol )
				{
					if (dCol == nFull || !singletonDims[dCol] )
					{
						resFull.set( res.get( dReducedDims, dReducedDimsCol ), d, dCol );
						dReducedDimsCol++;
					}
				}
				dReducedDims++;
			}
		}

		// get subpixel offset before alignment
		final double[] subpixelOffset = new double[ nFull ];
		int d2 = 0;
		for ( int d = 0; d < nFull; ++d )
		{
			if ( singletonDims[d] )
			{
				// NOP, we did not calculate any transformation in this dimension
			}
			else
			{
				// correct for the int/real coordinate mess
				final double intervalSubpixelOffset1 = interval1.realMin( d2 ) - localOverlap1.realMin( d2 ); // a_s
				final double intervalSubpixelOffset2 = interval2.realMin( d2 ) - localOverlap2.realMin( d2 ); // b_s
				subpixelOffset[d] = ( intervalSubpixelOffset2 - intervalSubpixelOffset1 );
				d2++;
			}
		}

		// correct for subpixel offset
		final AffineTransform subpixelT = new AffineTransform( nFull );
		for (int d = 0; d<nFull; d++)
			subpixelT.set( subpixelOffset[d], d, nFull );
		resFull.preConcatenate( subpixelT );

		return new ValuePair<>( resFull, lkAlign.didConverge() ? lkAlign.getCurrentCorrelation(  Views.zeroMin( Views.interval( img2, interval2 ) ) ) : 0.0 );
	}
	/**
	 * The absolute shift of input2 relative to after PCM input1 (without t1 and
	 * t2 - they just help to speed it up)
	 * 

	 * @param input1 - zero-min interval, starting at (0,0,...)
	 * @param input2 - zero-min interval, starting at (0,0,...)
	 * @param t1 - translation of input1
	 * @param t2 - translation of input2
	 * @param params - stitching parameters
	 * @param service - executor service to use
	 * @param <T> pixel type input1
	 * @param <S> pixel type input2
	 * @return pair of shift vector and cross correlation coefficient or null if no shift could be determined
	 */
	public static <T extends RealType< T >, S extends RealType< S >> Pair< Translation, Double > getShift(
			final RandomAccessibleInterval< T > input1, final RandomAccessibleInterval< S > input2,
			final TranslationGet t1, final TranslationGet t2, final PairwiseStitchingParameters params,
			final ExecutorService service)
	{

		// check if we have singleton dimensions
		boolean[] singletonDims = new boolean[input1.numDimensions()];
		for ( int d = 0; d < input1.numDimensions(); ++d )
			singletonDims[d] = !(input1.dimension( d ) > 1 && input2.dimension( d ) > 1);
		// TODO: should we consider cases where a dimension is singleton in one image but not the other?

		final RealInterval transformed1 = TransformTools.applyTranslation( input1, params.useWholeImage ? new Translation3D() : t1, singletonDims );
		final RealInterval transformed2 = TransformTools.applyTranslation( input2, params.useWholeImage ? new Translation3D() : t2, singletonDims );

		final RandomAccessibleInterval< T > img1;
		final RandomAccessibleInterval< S > img2;

		// make sure it is zero-min and drop singleton dimensions
		if ( !Views.isZeroMin( input1 ) )
			img1 = Views.dropSingletonDimensions( Views.zeroMin( input1 ));
		else
			img1 = Views.dropSingletonDimensions(input1);

		if ( !Views.isZeroMin( input2 ) )
			img2 = Views.dropSingletonDimensions( Views.zeroMin( input2 ) );
		else
			img2 = Views.dropSingletonDimensions( input2 );

		// echo intervals
		if ( debug )
		{
			System.out.println( "1: " + Util.printInterval( img1 ) );
			System.out.println( "1: " + TransformationTools.printRealInterval( transformed1 ) );
			System.out.println( "2: " + Util.printInterval( img2 ) );
			System.out.println( "2: " + TransformationTools.printRealInterval( transformed2 ) );
		}

		// get overlap interval
		final RealInterval overlap = TransformTools.getOverlap( transformed1, transformed2 );

		if ( debug )
			System.out.println( "O: " + TransformationTools.printRealInterval( overlap ) );

		// not overlapping -> we wont be able to determine a shift
		if ( overlap == null )
			return null;

		// get overlap in images' coordinates
		final RealInterval localOverlap1 = TransformTools.getLocalOverlap( transformed1, overlap );
		final RealInterval localOverlap2 = TransformTools.getLocalOverlap( transformed2, overlap );

		// round to integer interval
		final Interval interval1 = TransformTools.getLocalRasterOverlap( localOverlap1 );
		final Interval interval2 = TransformTools.getLocalRasterOverlap( localOverlap2 );

		if ( debug )
		{
			// echo intervals
			System.out.println( "1: " + TransformationTools.printRealInterval( localOverlap1 ) );
			System.out.println( "1: " + Util.printInterval( interval1 ) );
			System.out.println( "2: " + TransformationTools.printRealInterval( localOverlap2 ) );
			System.out.println( "2: " + Util.printInterval( interval2 ) );
		}

		// check whether we have 0-sized (or negative sized) or unequal raster overlapIntervals
		// (this should just happen with overlaps < 1px in some dimension)
		// ignore this pair in that case
		// FIXED for downsampling=2 caused by up/down-rounding (see TransformTools.getLocalRasterOverlap)
		// TODO: in pre-transformed views (e.g. both rotated), we might sometimes have unequal overlap due to numerical imprecision?
		//    -> look into this (still not fixed!) >> should be fixed now
		for (int d = 0; d < interval1.numDimensions(); ++d)
		{
			if ( interval1.dimension( d ) <= 0 || interval2.dimension( d ) <= 0 )
			{
				IOFunctions.println( "Rastered overlap between volumes is zero, skipping." );
				return null;
			}

			if ( interval1.dimension( d ) != interval2.dimension( d ) )
			{
				IOFunctions.println( "Rastered overlap between volumes in dim " + d + " is unequal ("+interval1.dimension( d )+"<>"+interval2.dimension( d )+"), skipping." );
				return null;
			}
		}

		//
		// call the phase correlation
		//
		final int[] extension = new int[img1.numDimensions()];
		Arrays.fill( extension, 10 );

		//
		// the min overlap is in percent of the current overlap interval
		//
		long minOverlap = 1;
		for (int d = 0; d < interval1.numDimensions(); d++)
			minOverlap *= interval1.dimension( d );
		minOverlap *= params.minOverlap;
		//System.out.println( "Min overlap is: " + minOverlap );

		if ( debug )
			System.out.println( "FFT" );

		// TODO: Do not extend by mirror inside, but do that out here on the
		// full image,
		// so we feed it RandomAccessible + an Interval we want to use for the
		// PCM > also zero-min inside
		final RandomAccessibleInterval< FloatType > pcm = PhaseCorrelation2.calculatePCM(
				Views.zeroMin( Views.interval( img1, interval1 ) ), Views.zeroMin( Views.interval( img2, interval2 ) ),
				extension, new ArrayImgFactory< FloatType >(), new FloatType(),
				new ArrayImgFactory< ComplexFloatType >(), new ComplexFloatType(), service );

		normalizePCM( pcm, service );

		final PhaseCorrelationPeak2 shiftPeak = PhaseCorrelation2.getShift( pcm,
				Views.zeroMin( Views.interval( img1, interval1 ) ), Views.zeroMin( Views.interval( img2, interval2 ) ),
				params.peaksToCheck, minOverlap, params.doSubpixel, params.interpolateCrossCorrelation, service );

		//System.out.println( "Actual overlap of best shift is: " + shiftPeak.getnPixel() );

		// the best peak is horrible or no peaks were found at all, return null
		if ( shiftPeak == null || Double.isInfinite( shiftPeak.getCrossCorr() ) )
			return null;

		final RealLocalizable shift;

		if ( shiftPeak.getSubpixelShift() == null )
			shift = shiftPeak.getShift();
		else
			shift = shiftPeak.getSubpixelShift();

		// final, relative shift
		final double[] finalShift = new double[input1.numDimensions()];
		int d2 = 0;
		for ( int d = 0; d < input1.numDimensions(); ++d )
		{
			// we ignored these axes during phase correlation -> set their shift to 0
			if (singletonDims[d])
			{
				finalShift[d] = 0.0;
			}
			else
			{
				// correct for the int/real coordinate mess
				final double intervalSubpixelOffset1 = interval1.realMin( d2 ) - localOverlap1.realMin( d2 ); // a_s
				final double intervalSubpixelOffset2 = interval2.realMin( d2 ) - localOverlap2.realMin( d2 ); // b_s
	
				final double localRasterShift = shift.getDoublePosition( d2 ); // d'
				if ( PairwiseStitching.debug )
					System.out.println( intervalSubpixelOffset1 + "," + intervalSubpixelOffset2 + "," + localRasterShift );
				final double localRelativeShift = localRasterShift - ( intervalSubpixelOffset2 - intervalSubpixelOffset1 );
	
				finalShift[d] = localRelativeShift;
				d2++;
			}

			// if we used the whole image, subtract existing shift
			if (params.useWholeImage)
			{
				finalShift[d] -= t2.getTranslation( d ) - t1.getTranslation( d );
			}
		}

		return new ValuePair< >( new Translation(finalShift), shiftPeak.getCrossCorr() );
	}

	public static void normalizePCM( final RandomAccessibleInterval< FloatType > pcm, final ExecutorService service )
	{
		// so that the peak doesn't stick out too much, that interferes with the subpixel detection
		final float min = min( pcm, service );

		adjustPCM( pcm, min, service );
	}

	public static float min( final RandomAccessibleInterval< FloatType > img, final ExecutorService taskExecutor )
	{
		final IterableInterval< FloatType > iterable = Views.iterable( img );
		
		// split up into many parts for multithreading
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( iterable.size() );

		// set up executor service
		final ArrayList< Callable< Float > > tasks = new ArrayList<>();

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< Float >() 
					{
						@Override
						public Float call() throws Exception
						{
							float min = Float.MAX_VALUE;

							final Cursor< FloatType > c = iterable.cursor();
							c.jumpFwd( portion.getStartPosition() );

							for ( long j = 0; j < portion.getLoopSize(); ++j )
								min = Math.min( min, c.next().get() );

							// min & max of this portion
							return min;
						}
					});
		}

		// run threads and combine results
		float min = Float.MAX_VALUE;

		try
		{
			// invokeAll() returns when all tasks are complete
			final List< Future< Float > > futures = taskExecutor.invokeAll( tasks );

			for ( final Future< Float > future : futures )
				min = Math.min( min, future.get() );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to compute min: " + e );
			e.printStackTrace();
			return Float.NaN;
		}

		return min;
	}

	public static void adjustPCM( final RandomAccessibleInterval< FloatType > img, final float min, final ExecutorService taskExecutor )
	{
		final IterableInterval< FloatType > iterable = Views.iterable( img );
		
		// split up into many parts for multithreading
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( iterable.size() );

		// set up executor service
		final ArrayList< Callable< Void > > tasks = new ArrayList<>();

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< Void >() 
					{
						@Override
						public Void call() throws Exception
						{
							final Cursor< FloatType > c = iterable.cursor();
							c.jumpFwd( portion.getStartPosition() );

							for ( long j = 0; j < portion.getLoopSize(); ++j )
							{
								final FloatType t = c.next();
								t.set( (float)Math.sqrt( t.get() - min + 0.01 ) );
							}

							return null;
						}
					});
		}

		try
		{
			// invokeAll() returns when all tasks are complete
			taskExecutor.invokeAll( tasks );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to subtract: " + e );
		}
	}

	public static <T extends RealType< T >, C extends Comparable< C >> List< PairwiseStitchingResult< C > > getPairwiseShiftsLucasKanade(
			final Map< C, RandomAccessibleInterval< T > > rais, final Map< C, TranslationGet > translations,
			final LucasKanadeParameters params, final ExecutorService service)
	{
		List< C > indexes = new ArrayList< >( rais.keySet() );
		Collections.sort( indexes );

		List< PairwiseStitchingResult< C > > result = new ArrayList< >();

		// got through all pairs with index1 < index2
		for ( int i = 0; i < indexes.size(); i++ )
		{
			for ( int j = i + 1; j < indexes.size(); j++ )
			{
				Pair< AffineTransform, Double > resT = getShiftLucasKanade( rais.get( indexes.get( i ) ), rais.get( indexes.get( j ) ),
						translations.get( indexes.get( i ) ), translations.get( indexes.get( j ) ), params, service );

				if ( resT != null )
				{
					Set<C> setA = new HashSet<>();
					setA.add( indexes.get( i ) );
					Set<C> setB = new HashSet<>();
					setA.add( indexes.get( j ) );
					Pair< Group<C>, Group<C> > key = new ValuePair<>(new Group<>(setA), new Group<>(setB));
					result.add( new PairwiseStitchingResult< C >( key, null, resT.getA() , resT.getB(), 0.0 ) );
				}
				
			}
		}

		return result;
	}


	public static <T extends RealType< T >, C extends Comparable< C >> List< PairwiseStitchingResult< C > > getPairwiseShifts(
			final Map< C, RandomAccessibleInterval< T > > rais, final Map< C, TranslationGet > translations,
			final PairwiseStitchingParameters params, final ExecutorService service)
	{
		List< C > indexes = new ArrayList< >( rais.keySet() );
		Collections.sort( indexes );

		List< PairwiseStitchingResult< C > > result = new ArrayList< >();

		// got through all pairs with index1 < index2
		for ( int i = 0; i < indexes.size(); i++ )
		{
			for ( int j = i + 1; j < indexes.size(); j++ )
			{
				final Pair< Translation, Double > resT = getShift( rais.get( indexes.get( i ) ), rais.get( indexes.get( j ) ),
						translations.get( indexes.get( i ) ), translations.get( indexes.get( j ) ), params, service );

				if ( resT != null )
				{
					Set<C> setA = new HashSet<>();
					setA.add( indexes.get( i ) );
					Set<C> setB = new HashSet<>();
					setA.add( indexes.get( j ) );
					Pair< Group<C>, Group<C> > key = new ValuePair<>(new Group<>(setA), new Group<>(setB));
					result.add( new PairwiseStitchingResult< C >( key, null, resT.getA(), resT.getB(), 0.0 ) );
				}
			}
		}

		return result;

	}

	public static void main(String[] args)
	{
		final AffineTransform3D m = new AffineTransform3D();
		double scale = 200;
		m.set( scale, 0.0f, 0.0f, 0.0f, 0.0f, scale, 0.0f, 0.0f, 0.0f, 0.0f, scale, 0.0f );

		final AffineTransform3D mShift = new AffineTransform3D();
		double shift = 100;
		mShift.set( 1.0f, 0.0f, 0.0f, shift, 0.0f, 1.0f, 0.0f, shift, 0.0f, 0.0f, 1.0f, shift );
		final AffineTransform3D mShift2 = new AffineTransform3D();
		double shift2x = 1200;
		double shift2y = 300;
		mShift2.set( 1.0f, 0.0f, 0.0f, shift2x, 0.0f, 1.0f, 0.0f, shift2y, 0.0f, 0.0f, 1.0f, 0.0f );

		final AffineTransform3D mShift3 = new AffineTransform3D();
		double shift3x = 500;
		double shift3y = 1300;
		mShift3.set( 1.0f, 0.0f, 0.0f, shift3x, 0.0f, 1.0f, 0.0f, shift3y, 0.0f, 0.0f, 1.0f, 0.0f );

		AffineTransform3D m2 = m.copy();
		AffineTransform3D m3 = m.copy();
		m.preConcatenate( mShift );
		m2.preConcatenate( mShift2 );
		m3.preConcatenate( mShift3 );

		Interval start = new FinalInterval( new long[] { -399, -399, 0 }, new long[] { 0, 0, 1 } );
		List< Interval > intervals = FractalSpimDataGenerator.generateTileList( start, 7, 6, 0.2f );

		List< Interval > falseStarts = FractalSpimDataGenerator.generateTileList( start, 7, 6, 0.30f );

		FractalSpimDataGenerator fsdg = new FractalSpimDataGenerator( 3 );
		fsdg.addFractal( m );
		fsdg.addFractal( m2 );
		fsdg.addFractal( m3 );

		Map< Integer, RandomAccessibleInterval< LongType > > rais = new HashMap< >();
		Map< Integer, TranslationGet > tr = new HashMap< >();

		List< TranslationGet > tileTranslations = FractalSpimDataGenerator.getTileTranslations( falseStarts );

		FractalImgLoader imgLoader = (FractalImgLoader) fsdg.generateSpimData( intervals ).getSequenceDescription()
				.getImgLoader();
		for ( int i = 0; i < intervals.size(); i++ )
		{
			rais.put( i, imgLoader.getImageAtInterval( intervals.get( i ) ) );
			tr.put( i, tileTranslations.get( i ) );
		}

		List< PairwiseStitchingResult< Integer > > pairwiseShifts = getPairwiseShifts( rais, tr,
				new PairwiseStitchingParameters(),
				Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() ) );

		
		Map< Integer, AffineGet > collect = tr.entrySet().stream().collect( Collectors.toMap( e -> 
			e.getKey(), e -> {AffineTransform3D res = new AffineTransform3D(); res.set( e.getValue().getRowPackedCopy() ); return res; } ));
		
		// TODO: replace with new globalOpt code
		
//		Map< Set<Integer>, AffineGet > globalOptimization = GlobalTileOptimization.twoRoundGlobalOptimization( new TranslationModel3D(),
//				rais.keySet().stream().map( ( c ) -> {Set<Integer> s = new HashSet<>(); s.add( c ); return s;}).collect( Collectors.toList() ), 
//				null, 
//				collect,
//				pairwiseShifts, new GlobalOptimizationParameters() );
//
//		System.out.println( globalOptimization );
	}

}
