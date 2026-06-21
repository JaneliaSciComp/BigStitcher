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

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ij.ImageJ;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.fft2.FFT;
import net.imglib2.algorithm.fft2.FFTMethods;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ComplexType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.stitcher.algorithm.PairwiseStitching;

public class PhaseCorrelation2 {
	
	
	
	/*
	 * calculate the phase correlation of fft1 and fft2, save result to res
	 * fft1 and fft2 will be altered by the function
	 * @param fft1 fft of first image
	 * @param fft2 fft of second image
	 * @param pcm 
	 * @param service
	 * @param <T>
	 * @param <S>
	 * @param <R>
	 */
	public static <T extends ComplexType<T>, S extends ComplexType<S>, R extends RealType<R>> void calculatePCMInPlace(
			RandomAccessibleInterval<T> fft1, RandomAccessibleInterval<S> fft2, RandomAccessibleInterval<R> pcm, ExecutorService service)
	{
		calculatePCM( fft1, fft1, fft2, fft2, pcm , service);
	}
	
	/*
	 * calculate the phase correlation of fft1 and fft2, save result to res
	 * fft1 and fft2 will NOT be altered by the function
	 * @param fft1
	 * @param fft1Copy - a temporary image same size as fft1 and fft2
	 * @param fft2
	 * @param fft2Copy - a temporary image same size as fft1 and fft2
	 * @param pcm
	 */
	public static <T extends ComplexType<T>, S extends ComplexType<S>, R extends RealType<R>> void calculatePCM(
			RandomAccessibleInterval<T> fft1, RandomAccessibleInterval<T> fft1Copy, RandomAccessibleInterval<S> fft2, RandomAccessibleInterval<S> fft2Copy, RandomAccessibleInterval<R> pcm,
			ExecutorService service)
	{
		// TODO: multithreaded & check for cursor vs randomaccess
		
		// normalize, save to copies
		PhaseCorrelation2Util.normalizeInterval(fft1, fft1Copy, service);
		PhaseCorrelation2Util.normalizeInterval(fft2, fft2Copy, service);
		// conjugate
		PhaseCorrelation2Util.complexConjInterval(fft2Copy, fft2Copy, service);
		// in-place multiplication
		PhaseCorrelation2Util.multiplyComplexIntervals(fft1Copy, fft2Copy, fft1Copy, service);
		FFT.complexToReal(fft1Copy, pcm, service);
	}
	
	
	/*
	 * calculate phase correlation of fft1 and fft2, return result in a new Img
	 * @param fft1
	 * @param fft2
	 * @return
	 */
	public static <T extends ComplexType<T>, S extends ComplexType<S>, R extends RealType<R>> RandomAccessibleInterval<R> calculatePCMInPlace(
			RandomAccessibleInterval<T> fft1, RandomAccessibleInterval<S> fft2, ImgFactory<R> factory, R type, ExecutorService service){
		
		long[] paddedDimensions = new long[fft1.numDimensions()];
		long[] realSize = new long[fft1.numDimensions()];
		
		FFTMethods.dimensionsComplexToRealFast(fft1, paddedDimensions, realSize);
		RandomAccessibleInterval<R> res = factory.create(realSize, type);
		
		calculatePCMInPlace(fft1, fft2, res, service);
		
		return res;
	}
	
	/*
	 * calculate phase correlation of fft1 and fft2, doing the calculations in copies of the ffts, return result in a new Img
	 * @param fft1
	 * @param fft2
	 * @return
	 */
	public static <T extends ComplexType<T> & NativeType<T>, S extends ComplexType<S> & NativeType <S>, R extends RealType<R>> RandomAccessibleInterval<R> calculatePCM(
			RandomAccessibleInterval<T> fft1, RandomAccessibleInterval<S> fft2, ImgFactory<R> factory, R type, ExecutorService service){
		
		long[] paddedDimensions = new long[fft1.numDimensions()];
		long[] realSize = new long[fft1.numDimensions()];
		
		FFTMethods.dimensionsComplexToRealFast(fft1, paddedDimensions, realSize);
		RandomAccessibleInterval<R> res = factory.create(realSize, type);
		
		final T typeT = Views.iterable(fft1).firstElement().createVariable();
		final S typeS = Views.iterable(fft2).firstElement().createVariable();
		RandomAccessibleInterval< T > fft1Copy;
		RandomAccessibleInterval< S > fft2Copy;

		try
		{
			fft1Copy = factory.imgFactory( typeT ).create(fft1, typeT );
			fft2Copy = factory.imgFactory( typeS ).create(fft2, typeS );
		}
		catch ( IncompatibleTypeException e )
		{
			throw new RuntimeException( "Cannot instantiate Img for type " + typeS.getClass().getSimpleName() + " or " + typeT.getClass().getSimpleName() );
		}
		
		
		calculatePCM(fft1, fft1Copy, fft2, fft2Copy, res, service);
		
		return res;
	}
	
	/*
	 * calculate and return the phase correlation matrix of two images
	 * @param img1
	 * @param img2
	 * @return
	 */
	public static <T extends RealType<T>, S extends RealType<S>, R extends RealType<R>, C extends ComplexType<C>> RandomAccessibleInterval<R> calculatePCM(
			RandomAccessibleInterval<T> img1, RandomAccessibleInterval<S> img2, int[] extension,
			ImgFactory<R> factory, R type, ImgFactory<C> fftFactory, C fftType, ExecutorService service){

		
		// TODO: Extension absolute per dimension in pixels, i.e. int[] extension
		// TODO: not bigger than the image dimension because the second mirroring is identical to the image
		
		Dimensions extSize = PhaseCorrelation2Util.getExtendedSize(img1, img2, extension);
		long[] paddedDimensions = new long[extSize.numDimensions()];
		long[] fftSize = new long[extSize.numDimensions()];
		FFTMethods.dimensionsRealToComplexFast(extSize, paddedDimensions, fftSize);
		
		RandomAccessibleInterval<C> fft1 = fftFactory.create(fftSize, fftType);
		RandomAccessibleInterval<C> fft2 = fftFactory.create(fftSize, fftType);
		
		FFT.realToComplex(Views.interval(PhaseCorrelation2Util.extendImageByFactor(img1, extension), 
				FFTMethods.paddingIntervalCentered(img1, new FinalInterval(paddedDimensions))), fft1, service);
		FFT.realToComplex(Views.interval(PhaseCorrelation2Util.extendImageByFactor(img2, extension), 
				FFTMethods.paddingIntervalCentered(img2, new FinalInterval(paddedDimensions))), fft2, service);
		
		RandomAccessibleInterval<R> pcm = calculatePCMInPlace(fft1, fft2, factory, type, service);
		return pcm;
		
	}

	/*
	 * calculate PCM with default extension
	 * @param img1
	 * @param img2
	 * @return
	 */
	public static <T extends RealType<T>, S extends RealType<S>, R extends RealType<R>, C extends ComplexType<C>> RandomAccessibleInterval<R> calculatePCM(
			RandomAccessibleInterval<T> img1, RandomAccessibleInterval<S> img2, ImgFactory<R> factory, R type,
			ImgFactory<C> fftFactory, C fftType, ExecutorService service) {
		
		int [] extension = new int[img1.numDimensions()];
		Arrays.fill(extension, 10);
		return calculatePCM(img1, img2, extension, factory, type, fftFactory, fftType, service);
	}

	/**
	 * calculate the shift between two images from the phase correlation matrix
	 * @param pcm the phase correlation matrix of img1 and img2
	 * @param img1 source image 1
	 * @param img2 source image 2
	 * @param nHighestPeaks the number of peaks in pcm to check via cross. corr.
	 * @param minOverlap minimal overlap (in pixels)
	 * @param subpixelAccuracy whether to do subpixel shift peak localization or not
	 * @param interpolateSubpixel whether to interpolate the subpixel shift in cross. corr.
	 * @param service thread pool
	 * @param <R> PCM pixel type
	 * @param <T> image 1 pixel type
	 * @param <S> image 2 pixel type
	 * @return best (highest c.c.) shift peak
	 */
	public static <T extends RealType<T>, S extends RealType<S>, R extends RealType<R>> PhaseCorrelationPeak2 getShift(
			RandomAccessibleInterval<R> pcm, RandomAccessibleInterval<T> img1, RandomAccessibleInterval<S> img2, int nHighestPeaks,
			long minOverlap, boolean subpixelAccuracy, boolean interpolateSubpixel, ExecutorService service)
	{
		if ( PairwiseStitching.debug )
			System.out.println( "PCM" );

		List<PhaseCorrelationPeak2> peaks = PhaseCorrelation2Util.getPCMMaxima(pcm, service, nHighestPeaks, subpixelAccuracy);
		//peaks = PhaseCorrelation2Util.getHighestPCMMaxima(peaks, nHighestPeaks);

		if ( PairwiseStitching.debug )
			System.out.println( "expand" );

		PhaseCorrelation2Util.expandPeakListToPossibleShifts(peaks, pcm, img1, img2);

		if ( PairwiseStitching.debug )
			System.out.print( "cross " );

		long t = System.currentTimeMillis();

		PhaseCorrelation2Util.calculateCrossCorrParallel(peaks, img1, img2, minOverlap, service, interpolateSubpixel);

		if ( PairwiseStitching.debug )
		{
			System.out.println( (System.currentTimeMillis() - t) );
			System.out.println( "sort" );
		}

		Collections.sort(peaks, Collections.reverseOrder(new PhaseCorrelationPeak2.ComparatorByCrossCorrelation()));

		if ( PairwiseStitching.debug )
			System.out.println( "done" );

		if (peaks.size() > 0)
			return peaks.get(0);
		else
			return null;
	}

	/**
	 * get shift, do not interpolate subpixel offset for cross correlation 
	 * @param pcm the phase correlation matrix of img1 and img2
	 * @param img1 source image 1
	 * @param img2 source image 2
	 * @param nHighestPeaks the number of peaks in pcm to check via cross. corr.
	 * @param minOverlap minimal overlap (in pixels)
	 * @param subpixelAccuracy whether to do subpixel shift peak localization or not
	 * @param service thread pool
	 * @param <R> PCM pixel type
	 * @param <T> image 1 pixel type
	 * @param <S> image 2 pixel type
	 * @return best (highest c.c.) shift peak
	 */
	public static <T extends RealType<T>, S extends RealType<S>, R extends RealType<R>> PhaseCorrelationPeak2 getShift(
			RandomAccessibleInterval<R> pcm, RandomAccessibleInterval<T> img1, RandomAccessibleInterval<S> img2, int nHighestPeaks,
			long minOverlap, boolean subpixelAccuracy, ExecutorService service)
	{
		return getShift( pcm, img1, img2, nHighestPeaks, minOverlap, subpixelAccuracy, false, service );
	}

	/**
	 * calculate the sift with default parameters (5 highest pcm peaks are considered, no minimum overlap, temporary thread pool,
	 * no subpixel interpolation)
	 * @param pcm the phase correlation matrix of img1 and img2
	 * @param img1 source image 1
	 * @param img2 source image 2
	 * @param <R> PCM pixel type
	 * @param <T> image 1 pixel type
	 * @param <S> image 2 pixel type
	 * @return best (highest c.c.) shift peak
	 */
	public static <T extends RealType<T>, S extends RealType<S>, R extends RealType<R>> PhaseCorrelationPeak2 getShift(
			RandomAccessibleInterval<R> pcm, RandomAccessibleInterval<T> img1, RandomAccessibleInterval<S> img2)
	{
		ExecutorService service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		PhaseCorrelationPeak2 res = getShift(pcm, img1, img2, 5, 0, true, false, service);
		service.shutdown();
		return res;
	}

	public static void main(String[] args) {
		
		new ImageJ();
		
		Img<FloatType> img1 = ImgLib2Util.openAs32Bit(new File("src/main/resources/img1singleplane.tif"));
		Img<FloatType> img2 = ImgLib2Util.openAs32Bit(new File("src/main/resources/img2singleplane.tif"));
		
		ExecutorService service = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
		
		RandomAccessibleInterval<FloatType> pcm = calculatePCM(img1, img2, new ArrayImgFactory<FloatType>(), new FloatType(),
				new ArrayImgFactory<ComplexFloatType>(), new ComplexFloatType(), service );
		
		PhaseCorrelationPeak2 shiftPeak = getShift(pcm, img1, img2);
		
		RandomAccessibleInterval<FloatType> res = PhaseCorrelation2Util.dummyFuse(img1, img2, shiftPeak,service);
				
		ImageJFunctions.show(res);
	}

}
