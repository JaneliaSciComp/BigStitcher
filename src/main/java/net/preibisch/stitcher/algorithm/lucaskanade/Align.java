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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import Jama.Matrix;

import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.algorithm.phasecorrelation.ImgLib2Util;
import net.imglib2.algorithm.phasecorrelation.PhaseCorrelation2Util;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.iterator.LocalizingIntervalIterator;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.realtransform.TranslationGet;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximalGroupOverlap;
import net.preibisch.mvrecon.process.downsampling.Downsample;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.stitcher.algorithm.TransformTools;

public class Align<T extends RealType< T >>
{
	final RandomAccessibleInterval< T > template;

	final WarpFunction warpFunction;

	/**
	 * Number of dimensions in the image
	 */
	final int n;

	/**
	 * Number of parameters of the warp function
	 */
	final int numParameters;

	final AffineTransform currentTransform;

	/**
	 * Image of <em>n+1</em> dimensions to store the steepest descent images of
	 * the template image at the identity warp. Dimension <em>n</em> is used to
	 * index the parameters of the warp function. For example, the partial
	 * derivative of the template image intensity by parameter 2 of the warp
	 * function at pixel <em>(x,y)</em> is stored at position <em>(x,y,1)</em>.
	 */
	final Img< FloatType > descent;

	/**
	 * Inverse of the Hessian matrix.
	 */
	double[][] Hinv;

	/**
	 * The error image for the last iteration shows the difference between the
	 * template and the warped (with {@link #currentTransform}) image.
	 */
	final Img< FloatType > error;
	
	boolean lastAlignConverged;
	
	/**
	 * returns true if the last align() call did not run for the maximum allowed number of iterations
	 * @return true or false
	 */
	public boolean didConverge()
	{
		return lastAlignConverged;
	}
	
	public void setCurrentTransform(AffineGet tr)
	{
		this.currentTransform.set( tr );
	}

	public Align(final RandomAccessibleInterval< T > template, final ImgFactory< FloatType > factory, WarpFunction model)
	{
		this.template = template;

		n = template.numDimensions();
		warpFunction = model;
		numParameters = warpFunction.numParameters();
		
		currentTransform = new AffineTransform( n );
		
		final long[] dim = new long[n + 1];
		for ( int d = 0; d < n; ++d )
			dim[d] = template.dimension( d );
		dim[n] = n;
		final Img< FloatType > gradients = factory.create( dim, new FloatType() );
		gradients( Views.extendBorder( template ), gradients );

		dim[n] = numParameters;
		descent = factory.create( dim, new FloatType() );
		computeSteepestDescents( gradients, warpFunction, descent );

		Hinv = computeInverseHessian( descent );

		error = factory.create( template, new FloatType() );
	}

	/**
	 * Compute the steepest descent images of the template at the identity warp.
	 * Each steepest descent image comprises the partial derivatives of template
	 * intensities with respect to one parameter of the warp function.
	 *
	 * The result is stored in the <em>n+1</em> dimensional target
	 * image. Dimension <em>n</em> is used to index the partial derivative. For
	 * example, the partial derivative by the second parameter of the warp
	 * function is stored in slice <em>n=1</em>.
	 *
	 * @param gradients
	 *            n+1 dimensional image of partial derivatives of the template.
	 *            Dimension n is used to index the partial derivative. For
	 *            example, the partial derivative by Y is stored in slice n=1.
	 * @param warpFunction
	 *            The warp function to be applied to the template. The partial
	 *            derivatives of template intensities with respect to the
	 *            parameters of this warp function are computed.
	 * @param target
	 *            Image of <em>n+1</em> dimensions to store the steepest descent
	 *            Dimension <em>n</em> is used to index the parameters of the
	 *            warp function. For example, the partial derivative of the
	 *            template image intensity by parameter 2 of the warp function
	 *            at pixel <em>(x,y)</em> is stored at position <em>(x,y,1)</em>
	 * @param <T>
	 *            pixel type
	 *            .
	 */
	public static <T extends NumericType< T >> void computeSteepestDescents(
			final RandomAccessibleInterval< T > gradients, final WarpFunction warpFunction,
			final RandomAccessibleInterval< T > target)
	{
		final int n = gradients.numDimensions() - 1;
		final int numParameters = warpFunction.numParameters();
		final T tmp = Util.getTypeFromInterval( gradients ).createVariable();
		for ( int p = 0; p < numParameters; ++p )
		{
			for ( int d = 0; d < n; ++d )
			{
				final Cursor< T > gd = Views.flatIterable( Views.hyperSlice( gradients, n, d ) ).localizingCursor();
				for ( final T t : Views.flatIterable( Views.hyperSlice( target, n, p ) ) )
				{
					tmp.set( gd.next() );
					tmp.mul( warpFunction.partial( gd, d, p ) );
					t.add( tmp );
				}
			}
		}
	}

	/**
	 * Compute the inverse Hessian matrix from the the steepest descent images.
	 * @param descent descent image
	 * @param <T> pixel type
	 * @return Hessian
	 */
	public static <T extends RealType< T >> double[][] computeInverseHessian(
			final RandomAccessibleInterval< T > descent)
	{
		final int n = descent.numDimensions() - 1;
		final int numParameters = (int) descent.dimension( n );
		final long[] dim = new long[n + 1];
		descent.dimensions( dim );
		dim[n] = 1;
		final LocalizingIntervalIterator pos = new LocalizingIntervalIterator( dim );
		final RandomAccess< T > r = descent.randomAccess();
		final double[] deriv = new double[numParameters];
		final double[][] H = new double[numParameters][numParameters];
		while ( pos.hasNext() )
		{
			pos.fwd();
			r.setPosition( pos );
			for ( int p = 0; p < numParameters; ++p )
			{
				deriv[p] = r.get().getRealDouble();
				r.fwd( n );
			}
			for ( int i = 0; i < numParameters; ++i )
				for ( int j = 0; j < numParameters; ++j )
					H[i][j] += deriv[i] * deriv[j];
		}
		return new Matrix( H ).inverse().getArray();
	}

	public double getCurrentCorrelation(final RandomAccessibleInterval< T > image)
	{
		final RealRandomAccessible< T > interpolated = Views.interpolate( Views.extendBorder( image ), new NLinearInterpolatorFactory< T >() );
		final RandomAccessible< T > warped = RealViews.affine( interpolated, currentTransform );
		return PhaseCorrelation2Util.getCorrelation( Views.interval( warped, template ), template );
	}

	/*
	 * Computed and return the affine transform that aligns image to template.
	 */
	public AffineTransform align(
			final RandomAccessibleInterval< T > image,
			final int maxIterations,
			final double minParameterChange,
			final ExecutorService service )
	{
		lastAlignConverged = false;

		currentTransform.set( new AffineTransform( n ) );
		int i = 0;
		while ( i < maxIterations )
		{
			++i;
			if ( alignStep( image, service ) < minParameterChange )
			{
				lastAlignConverged = true;
				break;
			}
		}
		System.out.println( "computed " + i + " iterations." );
		return currentTransform;
	}

	double alignStep(final RandomAccessibleInterval< T > image, ExecutorService service)
	{
		// compute error image = warped image - template
		computeDifference( Views.extendBorder( image ), currentTransform, template, error, service, Runtime.getRuntime().availableProcessors() * 2 );

		// compute transform parameter update
		final double[] gradient = new double[numParameters];

		ArrayList< Callable< Void > > calls =  new ArrayList< Callable<Void> >();
		for ( int p = 0; p < numParameters; ++p )
		{
			final int pInner = p;
			Callable< Void > callable = new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					double gradT = 0;
					final Cursor< FloatType > err = Views.flatIterable( error ).cursor();
					for ( final FloatType t : Views.flatIterable( Views.hyperSlice( descent, n, pInner ) ) )
						gradT += t.getRealDouble() * err.next().getRealDouble();
					gradient[pInner] = gradT;
					return null;

				}
			};

			calls.add( callable );

		}

		List<Future<Void>> futures = null;

		try
		{
			futures = service.invokeAll( calls );
			for (Future<Void> f : futures)
				f.get();
		}
		catch ( InterruptedException | ExecutionException e)
		{
			e.printStackTrace();
		}

		final double[] dp = new double[numParameters];
		LinAlgHelpers.mult( Hinv, gradient, dp );

		// udpate transform
		currentTransform.preConcatenate( warpFunction.getAffine( dp ) );

		// return norm of parameter update vector
		return LinAlgHelpers.length( dp );
	}

	/**
	 * Compute the partial derivative of source in a particular dimension.
	 *
	 * @param source
	 *            source image, has to provide valid data in the interval of the
	 *            gradient image plus a one pixel border in dimension.
	 * @param target
	 *            output image, the partial derivative of source in the
	 *            specified dimension.
	 * @param dimension
	 *            along which dimension the partial derivatives are computed
	 * @param <T> pixel type source
	 * @param <S> pixel type target
	 */
	public static < T extends RealType< T >, S extends RealType< S > > void gradient(
			final RandomAccessible< T > source,
			final RandomAccessibleInterval< S > target,
			final int dimension )
	{
		final Cursor< T > front = Views.flatIterable(
				Views.interval( source,
						Intervals.translate( target, 1, dimension ) ) ).cursor();
		final Cursor< T > back = Views.flatIterable(
				Views.interval( source,
						Intervals.translate( target, -1, dimension ) ) ).cursor();
		for( final S t : Views.flatIterable( target ) )
		{
			t.setReal( front.next().getRealDouble() - back.next().getRealDouble());
			t.mul( 0.5 );
		}
	}

	/**
	 * Compute the partial derivatives of source every dimension.
	 *
	 * @param source
	 *            n dimensional source image, has to provide valid data in the
	 *            interval of the gradient image plus a one pixel border in
	 *            every dimension.
	 * @param target
	 *            n+1 dimensional output image. Dimension n is used to index the
	 *            partial derivative. For example, the partial derivative by Y
	 *            is stored in slice n=1.
	 * @param <T> pixel type source
	 * @param <S> pixel type target
	 */
	public static < T extends RealType< T >, S extends RealType<S> > void gradients(
			final RandomAccessible< T > source,
			final RandomAccessibleInterval< S > target )
	{
		final int n = source.numDimensions();
		for ( int d = 0; d < n; ++d )
			gradient( source, Views.hyperSlice( target, n, d ), d );
	}

	/**
	 * Compute the pixel-wise difference between an affine-transformed source
	 * image and a target image.
	 *
	 * @param source
	 *            The source image.
	 * @param transform
	 *            A coordinate transformation to apply to the source image.
	 * @param target
	 *            The target image.
	 * @param difference
	 *            Output image. The pixel-wise difference between the
	 *            transformed source image and the target image is stored here.
	 * @param service
	 *            thread pool for difference calculation
	 * @param nTasks
	 *            number of image parts that are processed in parallel
	 * @param <T> pixel type source
	 * @param <S> pixel type target
	 */
	public static < T extends RealType< T >,  S extends RealType< S > > void computeDifference(
			final RandomAccessible< T > source,
			final AffineTransform transform,
			final RandomAccessible< T > target,
			final RandomAccessibleInterval< S > difference,
			final ExecutorService service,
			final int nTasks)
	{
		final RealRandomAccessible< T > interpolated = Views.interpolate( source, new NLinearInterpolatorFactory< T >() );
		final RandomAccessible< T > warped = RealViews.affine( interpolated, transform );

		final long stepSize = Views.iterable( difference ).size() / nTasks;

		final List<Callable< Void >> tasks = new ArrayList<>();
		final AtomicInteger ai = new AtomicInteger( 0 );
		for (int iO = 0; iO<nTasks; iO++)
		{
			tasks.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					final int i = ai.getAndIncrement();
					final Cursor< T > cw = Views.flatIterable( Views.interval( warped, difference ) ).cursor();
					final Cursor< T > ct = Views.flatIterable( Views.interval( target, difference ) ).cursor();
					final Cursor< S > cd = Views.flatIterable( difference ).cursor();

					cw.jumpFwd( stepSize * i );
					ct.jumpFwd( stepSize * i );
					cd.jumpFwd( stepSize * i );

					final long end = i == nTasks - 1 ? Views.iterable( difference ).size() - stepSize * i : stepSize;
					int count = 0;
					while (count++ < end)
					{
						cd.next().setReal( ( cw.next().getRealDouble() - ct.next().getRealDouble() ));
					}
					return null;
				}
			} );
		}

		try
		{
			List< Future< Void > > futures = service.invokeAll( tasks );
			for (Future< Void > f: futures)
				f.get();
		}
		catch ( InterruptedException | ExecutionException e )
		{
			e.printStackTrace();
		}
	}


	public static void main(String[] args)
	{
		Img< FloatType > a = ImgLib2Util.openAs32Bit( new File( "73.tif.zip" ) );
		Img< FloatType > b = ImgLib2Util.openAs32Bit( new File( "74.tif.zip" ) );

		TranslationGet t1 = new Translation3D();
		TranslationGet t2 = new Translation3D(460, 0, 0);
		ArrayList< Pair< RealInterval, AffineGet > > views = new ArrayList<Pair<RealInterval, AffineGet>>();
		views.add( new ValuePair< RealInterval, AffineGet >( a, t1 ) );
		views.add( new ValuePair< RealInterval, AffineGet >( b, t2 ) );

		RealInterval overlap = BoundingBoxMaximalGroupOverlap.getMinBoundingIntervalSingle( views );

		final RealInterval transformed1 = TransformTools.applyTranslation( a, t1, new boolean[] {false, false, false} );
		final RealInterval transformed2 = TransformTools.applyTranslation( b, t2, new boolean[] {false, false, false} );

		// get overlap in images' coordinates
		final RealInterval localOverlap1 = TransformTools.getLocalOverlap( transformed1, overlap );
		final RealInterval localOverlap2 = TransformTools.getLocalOverlap( transformed2, overlap );

		// round to integer interval
		final Interval interval1 = TransformTools.getLocalRasterOverlap( localOverlap1 );
		final Interval interval2 = TransformTools.getLocalRasterOverlap( localOverlap2 );

		//final WarpFunction warp = new TranslationWarp(3);
		final WarpFunction warp = new RigidWarp(3);
		//final WarpFunction warp = new AffineWarp( 3 );

		// rotate second image
		AffineTransform3D rot = new AffineTransform3D();
		rot.rotate( 2, 2 * Math.PI / 180 );
		RandomAccessibleInterval< FloatType > rotated = Views.interval(
				RealViews.affine( 
						Views.interpolate( Views.extendBorder( Views.zeroMin( Views.interval( b, interval2 ) ) ), new NLinearInterpolatorFactory<>() ),
						rot.copy() ),
				interval2);

		// show input
		new ImageJ();
		ImageJFunctions.show( Views.interval( a,  interval1 ), "target" );
		ImageJFunctions.show( rotated, "in");

		final ExecutorService service = Executors.newFixedThreadPool( Threads.numThreads() );

		// downsample input
		RandomAccessibleInterval<FloatType> simple2x1 = Downsample.simple2x(
				Views.zeroMin(Views.interval(a, interval1)),
				new boolean[] { true, true, false });
		RandomAccessibleInterval<FloatType> simple2x2 = Downsample.simple2x(
				Views.zeroMin(Views.interval(rotated, interval2)),
				new boolean[] { true, true, false } );

		// align

		//Align< FloatType > lk = new Align<>( Views.zeroMin( Views.interval( a, interval1 ) ), new ArrayImgFactory<>(), warp );
		Align< FloatType > lk = new Align<>( simple2x1, new ArrayImgFactory<>( new FloatType()), warp );
		//System.out.println( Util.printCoordinates( lk.align( Views.zeroMin( Views.interval( b, interval2 ) ), 100, 0.01 ).getRowPackedCopy() ) );
		//final AffineTransform transform = lk.align( Views.zeroMin( rotated ), 100, 0.01 );
		final AffineTransform transform = lk.align( simple2x2, 100, 0.01, service );

		service.shutdown();
	
		final AffineTransform scale = new AffineTransform( 3 );
		scale.set( 2, 0, 0 );
		scale.set( 1, 1, 1 );

		transform.preConcatenate( scale );

		// transformation matrix
		System.out.println( Util.printCoordinates( transform.getRowPackedCopy() ) );

		// correct input and show
		RandomAccessibleInterval< FloatType > backRotated = Views.interval(
				RealViews.affine( 
						Views.interpolate( Views.extendBorder( Views.zeroMin( Views.interval( b, interval2 ) ) ), new NLinearInterpolatorFactory<>() ),
						rot.copy().preConcatenate( transform ).copy() ),
				interval2);

		ImageJFunctions.show( backRotated, "out" );

		// constructor needs column packed matrix, therefore the transpose
		Matrix mt = new Matrix( transform.getRowPackedCopy(), 4).transpose();
		Matrix rigid = mt.getMatrix( 0, 2, 0, 2 );

		// check whether result is rotation matrix (det == +-1, orthogonal)
		System.out.println( rigid.det() );
		System.out.println( Util.printCoordinates( rigid.times( rigid.transpose() ).getRowPackedCopy() ) );
	}
}
