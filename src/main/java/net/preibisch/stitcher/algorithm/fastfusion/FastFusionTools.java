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

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;


public class FastFusionTools
{

	private static final int[] ds = new int[] {1,2,4,8,16,32};

	/**
	 * add input image to output image with given offset
	 * @param in input image
	 * @param out output image
	 * @param translation shift
	 * @param pool thread pool
	 * @param <T> in pixel type
	 * @param <R> out pixel type
	 */
	public static <T extends RealType<T>, R extends RealType< R > > void addTranslated(
			IterableInterval< T > in,
			RandomAccessibleInterval< R > out,
			int[] translation,
			ExecutorService pool)
	{
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( in.size() );
		final ArrayList< Callable< Void > > calls = new ArrayList<>();
		for (final ImagePortion portion : portions)
		{
			calls.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					Cursor<T> inC = in.localizingCursor();
					RandomAccess< R > outRA = out.randomAccess();
					inC.jumpFwd( portion.getStartPosition() );
					for (long i=0; i<portion.getLoopSize(); i++)
					{
						inC.fwd();
						outRA.setPosition( inC );
						outRA.move( translation );

						// check whether we moved outside of destination image
						boolean oob = false;
						for (int d=0; d<out.numDimensions(); d++)
							if (outRA.getLongPosition( d ) > out.max( d ) || outRA.getLongPosition( d ) < out.min( d ) )
								oob = true;
						if (oob)
							continue;

						final double p = outRA.get().getRealDouble() + inC.get().getRealDouble();
						outRA.get().setReal( p );

					}

					return null;
				}
			} );
		}

		try
		{
			final List< Future< Void > > futures = pool.invokeAll( calls );
			for (final Future< Void > f : futures)
				f.get();
		}
		catch ( InterruptedException | ExecutionException e ) { e.printStackTrace(); }
	}

	
	public static <T extends RealType<T>, R extends RealType< R > > void alphaBlendTranslated(
			IterableInterval< T > in,
			RandomAccessibleInterval< R > out,
			int[] translation,
			ExecutorService pool)
	{
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( in.size() );
		final ArrayList< Callable< Void > > calls = new ArrayList<>();
		for (final ImagePortion portion : portions)
		{
			calls.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					Cursor<T> inC = in.localizingCursor();
					RandomAccess< R > outRA = out.randomAccess();
					inC.jumpFwd( portion.getStartPosition() );
					for (long i=0; i<portion.getLoopSize(); i++)
					{
						inC.fwd();
						outRA.setPosition( inC );
						outRA.move( translation );

						// check whether we moved outside of destination image
						boolean oob = false;
						for (int d=0; d<out.numDimensions(); d++)
							if (outRA.getLongPosition( d ) > out.max( d ) || outRA.getLongPosition( d ) < out.min( d ) )
								oob = true;
						if (oob)
							continue;

						// do alpha blend
						final double aO = outRA.get().getRealDouble();
						final double aI = inC.get().getRealDouble();
						final double aNew =  aO + (1 - aO) * aI ;
						outRA.get().setReal( aNew );

					}

					return null;
				}
			} );
		}

		try
		{
			final List< Future< Void > > futures = pool.invokeAll( calls );
			for (final Future< Void > f : futures)
				f.get();
		}
		catch ( InterruptedException | ExecutionException e ) { e.printStackTrace(); }
	}

	/**
	 * get linear interpolation of image with a given subpixel shift
	 * @param in input image
	 * @param type instance pixel data type of output
	 * @param off (subpixel) offset to apply to in
	 * @param pool thread pool
	 * @param <R> in pixel type
	 * @param <T> out pixel type
	 * @return interpolated image (size +1 in each dimension)
	 */
	public static <T extends RealType<T> & NativeType<T>, R extends RealType<R> > Pair<RandomAccessibleInterval<T>, RandomAccessibleInterval<T>> getLinearInterpolation(
			RandomAccessibleInterval< R > in,
			T type,
			float[] off,
			ExecutorService pool)
	{
		// save alpha of border pixels
		// otherwise, we get dark lines in output (unless we blend) 

		// create new image & alpha image with dimensions in.dimensions(d) + 1 for each axis d
		// get size
		long size = 1;
		for (int d=0; d<in.numDimensions(); d++)
			size *= (in.dimension( d ) + 1);
		final boolean needCellImg = size > (Math.pow( 2, 31 ) - 1);
		long[] dimensions = new long[in.numDimensions()];
		in.dimensions( dimensions );
		for (int d=0; d<in.numDimensions(); d++)
			dimensions[d] += 1;

		// allocate images
		final Img< T > interpolated = needCellImg ? new CellImgFactory<>( type ).create( dimensions ) : new ArrayImgFactory<>( type ).create( dimensions );
		final Img< T > weights = needCellImg ? new CellImgFactory<>( type ).create( dimensions ) : new ArrayImgFactory<>( type ).create( dimensions );
		addTranslated( Views.iterable( Views.zeroMin( in ) ), interpolated, Util.getArrayFromValue( 1, in.numDimensions() ), pool );

		// pass over image numDimensions times, interpolating along each axis
		final AtomicInteger ad = new AtomicInteger();
		for (int d=0; d<in.numDimensions(); d++)
		{
			final int dFinal = ad.getAndIncrement();
			// use the smallest slice in d as the strating point, break that into portions for MT
			final IntervalView< T > startSlice = Views.hyperSlice( interpolated, dFinal, 0 );
			final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( startSlice.size() );
			final ArrayList< Callable< Void > > calls = new ArrayList<>();
			for (final ImagePortion portion : portions)
			{
				calls.add( new Callable< Void >()
				{
					@Override
					public Void call() throws Exception
					{

						RandomAccess< T > outRA = interpolated.randomAccess();
						RandomAccess< T > weightRA = weights.randomAccess();
						Cursor<T> inC = startSlice.localizingCursor();
						inC.jumpFwd( portion.getStartPosition() );

						for (long l=0; l<portion.getLoopSize(); l++)
						{
							inC.fwd();
							
							// n-1D start coordinates to nD start coordinates
							int additionalDim = 0;
							int[] pos = new int[in.numDimensions()];
							for (int d2=0; d2<interpolated.numDimensions(); d2++)
							{
								pos[d2] = d2==dFinal ? 0 : inC.getIntPosition( d2 - additionalDim);
								if (d2==dFinal)
									additionalDim++;

								// do not set the first pixel for subsequent interpolations
								else
									if (pos[d2] == 0)
										continue;
							}

							// interpolate along d
							outRA.setPosition( pos );
							weightRA.setPosition( pos );

							// set first pixel, alpha
							weightRA.fwd( dFinal );
							final double w0 = weightRA.get().getRealDouble();
							weightRA.bck( dFinal );
							weightRA.get().setReal( (1.0 - off[dFinal]) );

							// value: copy second pixel
							final double x1p = outRA.get().getRealDouble();
							outRA.fwd( dFinal );
							final double x2p = outRA.get().getRealDouble();
							outRA.bck( dFinal );
							//outRA.get().setReal( dFinal==0 ? x2p : (w0 * x1p + x2p * (1.0 - off[dFinal])) / (w0 + (1.0 - off[dFinal]) ) );
							outRA.get().setReal( x2p );
							outRA.fwd( dFinal );

							for (int x=1; x<(interpolated.dimension( dFinal ) - 1); x++)
							{
								final double x1 = outRA.get().getRealDouble();
								outRA.fwd( dFinal );
								final double x2 = outRA.get().getRealDouble();
								outRA.bck( dFinal );
								outRA.get().setReal( (1.0 - off[dFinal]) * x1 + off[dFinal] * x2 );
								outRA.fwd( dFinal );

								// other pixels: weights stay the same
								weightRA.fwd( dFinal );
								final double w1 = weightRA.get().getRealDouble();
								weightRA.get().setReal((dFinal==0 ? 1.0 : w1) * 1.0f );
							}
	
							// last pixel
							//final double x1 = outRA.get().getRealDouble();
							//outRA.get().setReal( x1 );

							weightRA.fwd( dFinal );
							final double w2 = weightRA.get().getRealDouble();
							weightRA.get().setReal( (dFinal==0 ? 1.0 : w2) * off[dFinal] );
						}

						return null;
					}
				} );
			}
			try
			{
				final List< Future< Void > > futures = pool.invokeAll( calls );
				for (final Future< Void > f : futures)
					f.get();
			}
			catch ( InterruptedException | ExecutionException e ) { e.printStackTrace(); }
		}
		return new ValuePair< RandomAccessibleInterval<T>, RandomAccessibleInterval<T> >( interpolated, weights );
	}


	/**
	 * apply blending to an image, save weights to separate image
	 * @param image image to apply blending to
	 * @param weightImage image to save weights to
	 * @param renderOffset (subpixel) offset of the original image to the provided version
	 * @param border blank pixels on each border
	 * @param blending extent of blending on each border
	 * @param multiplyWeights false: just set weightImage to new weights, true: multiply existing weightImage
	 * @param pool thread pool
	 * @param <T> image pixel data type
	 * @param <R> weight image pixel data type
	 */
	public static <T extends RealType<T>, R extends RealType<R> > void applyWeights(
			final RandomAccessibleInterval< T > image,
			final RandomAccessibleInterval< R > weightImage,
			final float[] renderOffset,
			final float[] border, 
			final float[] blending,
			final boolean multiplyWeights,
			final ExecutorService pool)
	{
		final int n = image.numDimensions();
		final int[] min = new int[n];
		final int[] dimMinus1 = new int[n];
		for (int d=0; d<n; d++)
		{
			min[d] = (int) image.min( d );
			dimMinus1[d] = (int) image.dimension( d ) - 1;
		}

		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions(  Views.iterable( image ).size() );
		final ArrayList< Callable< Void > > calls = new ArrayList<>();
		for (final ImagePortion portion : portions)
		{
			calls.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					// NB: assuming equal iteration order here
					final Cursor< R > weightC = Views.iterable( weightImage ).localizingCursor();
					final Cursor<T> inC = Views.iterable( image ).localizingCursor();
					inC.jumpFwd( portion.getStartPosition() );
					weightC.jumpFwd( portion.getStartPosition() );
					final float[] position = new float[n];
					for (long i=0; i<portion.getLoopSize(); i++)
					{
						inC.fwd();
						weightC.fwd();
						inC.localize( position );
						for (int d=0; d<n; d++)
							position[d] -= renderOffset[d];
						final float w = BlendingTools.computeWeight( position, min, dimMinus1, border, blending, n );
						inC.get().setReal( inC.get().getRealFloat() * w);
						
						if (multiplyWeights)
						{
							weightC.get().setReal(weightC.get().getRealDouble() * w );
						}
						else
						{
							weightC.get().setReal( w );
						}
					}
					return null;
				}
			} );
		}

		try
		{
			final List< Future< Void > > futures = pool.invokeAll( calls );
			for (final Future< Void > f : futures)
				f.get();
		}
		catch ( InterruptedException | ExecutionException e ) { e.printStackTrace(); }
	}


	@Deprecated
	// only small performance gain (over virtual fusion) using this
	public static <T extends RealType< T >> void normalizeWeightsOnTheFly(
			final RandomAccessibleInterval< T > image,
			final List<Interval> intervals,
			final List<float[]> renderOffsets,
			final List<float[]> borders, 
			final List<float[]> blendings,
			final ExecutorService pool
			)
	{
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions(  Views.iterable( image ).size() );
		final ArrayList< Callable< Void > > calls = new ArrayList<>();
		final int n = image.numDimensions();
		for (final ImagePortion portion : portions)
		{
			calls.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					final Cursor<T> inC = Views.iterable( image ).localizingCursor();
					inC.jumpFwd( portion.getStartPosition() );
					final float[] position = new float[n];
					for (long i=0; i<portion.getLoopSize(); i++)
					{
						inC.fwd();
						inC.localize( position );
						
						double weightSum = 0;
						for (int j=0; j<intervals.size(); j++)
						{
							final float[] renderOffset = renderOffsets.get( j );
							final float[] border = borders.get( j );
							final float[] blending = blendings.get( j );
							final int[] min = new int[n];
							final int[] dimMinus1 = new int[n];
							for (int d=0; d<n; d++)
							{
								min[d] = (int) intervals.get( j ).min( d );
								dimMinus1[d] = (int) intervals.get( j ).dimension( d ) - 1;
							}
							
							final float[] positionWithOffset = position.clone();
							for (int d=0; d<n; d++)
								positionWithOffset[d] += renderOffset[d];
							weightSum += BlendingTools.computeWeight( positionWithOffset, min, dimMinus1, border, blending, n );
						}
						inC.get().setReal( weightSum == 0.0 ? 0.0 : inC.get().getRealDouble() / weightSum );
					}
					return null;
				}
			} );
		}

		try
		{
			final List< Future< Void > > futures = pool.invokeAll( calls );
			for (final Future< Void > f : futures)
				f.get();
		}
		catch ( InterruptedException | ExecutionException e ) { e.printStackTrace(); }
		
	}


	/**
	 *  divide a sum image (after adding all views) by accumulated weights
	 *  a weight == 0 will set the corresponding pixel value to zero
	 * @param image (summed) input image
	 * @param weights weights of each pixel 
	 * @param pool thread pool for parallel execution 
	 * @param <T> pixel type image
	 * @param <R> pixel type weights
	 */
	public static <T extends RealType< T >, R extends RealType< R >> void normalizeWeights(
			final RandomAccessibleInterval< T > image,
			final RandomAccessibleInterval< R > weights,
			final ExecutorService pool
			)
	{
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions(  Views.iterable( image ).size() );
		final ArrayList< Callable< Void > > calls = new ArrayList<>();
		for (final ImagePortion portion : portions)
		{
			calls.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					// NB: this assumes the iteration order of image and weights is equal
					// (which it is in the current use case)
					// TODO: implement more cleanly?
					final Cursor<T> inC = Views.iterable( image ).localizingCursor();
					final Cursor<R> wC = Views.iterable( weights ).localizingCursor();
					inC.jumpFwd( portion.getStartPosition() );
					wC.jumpFwd( portion.getStartPosition() );
					for (long i=0; i<portion.getLoopSize(); i++)
					{
						inC.fwd();
						wC.fwd();
						final double w = wC.get().getRealDouble();
						final double v = inC.get().getRealDouble();
						inC.get().setReal( w == 0.0 ? 0.0 : v/w );
					}
					return null;
				}
			} );
		}

		try
		{
			final List< Future< Void > > futures = pool.invokeAll( calls );
			for (final Future< Void > f : futures)
				f.get();
		}
		catch ( InterruptedException | ExecutionException e ) { e.printStackTrace(); }
		
	}


	public static <T extends RealType< T >, R extends RealType< R >> void multiplyEqualSizeImages(
			final RandomAccessibleInterval< T > iOut,
			final RandomAccessibleInterval< R > iMult,
			final ExecutorService pool
			)
	{
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions(  Views.iterable( iOut ).size() );
		final ArrayList< Callable< Void > > calls = new ArrayList<>();
		for (final ImagePortion portion : portions)
		{
			calls.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					// NB: this assumes the iteration order of image and weights is equal
					// (which it is in the current use case)
					// TODO: implement more cleanly?
					final Cursor<T> inC = Views.iterable( iOut ).localizingCursor();
					final Cursor<R> wC = Views.iterable( iMult ).localizingCursor();
					inC.jumpFwd( portion.getStartPosition() );
					wC.jumpFwd( portion.getStartPosition() );
					for (long i=0; i<portion.getLoopSize(); i++)
					{
						inC.fwd();
						wC.fwd();
						final double w = wC.get().getRealDouble();
						final double v = inC.get().getRealDouble();
						inC.get().setReal( w * v );
					}
					return null;
				}
			} );
		}

		try
		{
			final List< Future< Void > > futures = pool.invokeAll( calls );
			for (final Future< Void > f : futures)
				f.get();
		}
		catch ( InterruptedException | ExecutionException e ) { e.printStackTrace(); }
		
	}


	/**
	 * get the affine transform to map a downsampled image opened with DownsampleTools.openAndDownsample back to full resolution space. 
	 * @param imgLoader ImgLoader to do the laoding
	 * @param vd view description to load
	 * @param downsamplefactor by how much we downsample (has to be a power of two)
	 * @return the transform
	 */
	public static AffineGet getDownsamplingTransfomPowerOf2(
			final ImgLoader imgLoader,
			final ViewDescription vd,
			int downsamplefactor)
	{
		if (!MultiResolutionImgLoader.class.isInstance( imgLoader ))
			return new Scale3D( downsamplefactor, downsamplefactor, downsamplefactor );

		final MultiResolutionImgLoader mrImgLoader = ( MultiResolutionImgLoader ) imgLoader;
		final AffineTransform3D mipMapTransform = new AffineTransform3D();
		double[][] mipmapResolutions = mrImgLoader.getSetupImgLoader( vd.getViewSetupId() ).getMipmapResolutions();

		int dsx = downsamplefactor;
		int dsy = downsamplefactor;
		int dsz = downsamplefactor;
		int bestLevel = 0;
		for ( int level = 0; level < mipmapResolutions.length; ++level )
		{
			double[] factors = mipmapResolutions[ level ];

			// this fails if factors are not ints
			final int fx = (int)Math.round( factors[ 0 ] );
			final int fy = (int)Math.round( factors[ 1 ] );
			final int fz = (int)Math.round( factors[ 2 ] );

			if ( fx <= dsx && fy <= dsy && fz <= dsz && contains( fx, ds ) && contains( fy, ds ) && contains( fz, ds ))
				bestLevel = level;
		}

		final int fx = (int)Math.round( mipmapResolutions[ bestLevel ][ 0 ] );
		final int fy = (int)Math.round( mipmapResolutions[ bestLevel ][ 1 ] );
		final int fz = (int)Math.round( mipmapResolutions[ bestLevel ][ 2 ] );

		mipMapTransform.set( mrImgLoader.getSetupImgLoader( vd.getViewSetupId() ).getMipmapTransforms()[ bestLevel ] );

		dsx /= fx;
		dsy /= fy;
		dsz /= fz;

		final AffineTransform3D additonalDS = new AffineTransform3D();
		additonalDS.set( dsx, 0.0, 0.0, 0.0, 0.0, dsy, 0.0, 0.0, 0.0, 0.0, dsz, 0.0 );
		mipMapTransform.concatenate( additonalDS );
		return mipMapTransform;
	}


	/**
	 * check if integer i is in int array
	 * @param i the value
	 * @param values the array
	 * @return true if i in values else false
	 */
	private static final boolean contains( final int i, final int[] values )
	{
		for ( final int j : values )
			if ( i == j )
				return true;
		return false;
	}


	public static void main(String[] args)
	{
		final ImagePlus imp = IJ.openImage( "/Users/david/Desktop/stable HelaK-GFP-H2A.Z20000.tif" );
		new ImageJ();

		RandomAccessibleInterval< ? extends RealType > img = ImageJFunctions.wrapReal( imp );
		ArrayImg< FloatType, FloatArray > f = ArrayImgs.floats( 1024, 1024 );
		ArrayImg< FloatType, FloatArray > w = ArrayImgs.floats( 1024, 1024 );
		RandomAccessibleInterval< FloatType > interp = (RandomAccessibleInterval< FloatType >) getLinearInterpolation( img, new FloatType(), new float[] {0.5f,0.5f}, Executors.newSingleThreadExecutor() ).getA();
		
		RandomAccessibleInterval< FloatType > weight = new ArrayImgFactory( new FloatType() ).create( interp );
		applyWeights( interp, weight, new float[] {0.5f,0.5f}, new float[] {0,0}, new float[] {20,20}, false, Executors.newSingleThreadExecutor() );
		addTranslated( Views.iterable( interp ), f, new int[] {500, 700}, Executors.newSingleThreadExecutor() );
		addTranslated( Views.iterable( interp ), f, new int[] {400, 500}, Executors.newSingleThreadExecutor() );
		addTranslated( Views.iterable( weight ), w, new int[] {500, 700}, Executors.newSingleThreadExecutor() );
		addTranslated( Views.iterable( weight ), w, new int[] {400, 500}, Executors.newSingleThreadExecutor() );

		normalizeWeights( f, w, Executors.newSingleThreadExecutor() );
		
		ImageJFunctions.show( f );
	}
}
