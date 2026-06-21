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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccessible;
import net.imglib2.algorithm.localextrema.RefinedPeak;
import net.imglib2.algorithm.localextrema.SubpixelLocalization;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Pair;
import net.imglib2.view.Views;

public class PhaseCorrelationPeak2 {
	
	Localizable pcmLocation; // location in the raw PCM
	RealLocalizable subpixelPcmLocation; // subpixel localized PCM location

	Localizable shift; // corresponding shift between images
	RealLocalizable subpixelShift; // corresponding subpixel accurate shift between images

	double phaseCorr; // value in PCM
	double crossCorr; // crosscorrelation bewtween imgs
	long nPixel; // number of overlapping pixels
	
	public Localizable getPcmLocation() {
		return pcmLocation;
	}

	public void setPcmLocation(Localizable pcmLocation) {
		this.pcmLocation = pcmLocation;
	}

	public Localizable getShift() {
		return shift;
	}

	public void setShift(Localizable shift) {
		this.shift = shift;
	}

	public RealLocalizable getSubpixelPcmLocation() {
		return subpixelPcmLocation;
	}

	public void setSubpixelPcmLocation(RealLocalizable subpixelPcmLocation) {
		this.subpixelPcmLocation = subpixelPcmLocation;
	}

	public RealLocalizable getSubpixelShift() {
		return subpixelShift;
	}

	public void setSubpixelShift(RealLocalizable subpixelShift) {
		this.subpixelShift = subpixelShift;
	}

	public double getPhaseCorr() {
		return phaseCorr;
	}

	public void setPhaseCorr(double phaseCorr) {
		this.phaseCorr = phaseCorr;
	}

	public double getCrossCorr() {
		return crossCorr;
	}

	public void setCrossCorr(double crossCorr) {
		this.crossCorr = crossCorr;
	}

	public long getnPixel() {
		return nPixel;
	}

	public void setnPixel(long nPixel) {
		this.nPixel = nPixel;
	}
	
	/*
	 * constructor with just raw PCM location and value
	 * @param pcmPosition
	 * @param phaseCorr
	 */
	public PhaseCorrelationPeak2(Localizable pcmPosition, double phaseCorr)
	{
		this.pcmLocation = new Point( pcmPosition );
		this.shift = null;
		this.subpixelPcmLocation = null;
		this.subpixelShift = null;
		this.phaseCorr = phaseCorr;
		this.crossCorr = 0.0;
		this.nPixel = 0;
	}
	
	/*
	 * copy contructor
	 * @param src
	 */
	public PhaseCorrelationPeak2(PhaseCorrelationPeak2 src){
		this.pcmLocation = new Point(src.pcmLocation);
		this.shift = src.shift == null? null : new Point(src.shift);
		this.subpixelPcmLocation = src.subpixelPcmLocation == null ? null : new RealPoint( src.subpixelPcmLocation );
		this.subpixelShift = src.subpixelShift == null ? null : new RealPoint( src.subpixelShift );
		this.phaseCorr = src.phaseCorr;
		this.crossCorr = src.crossCorr;
		this.nPixel = src.nPixel;
	}
	
	public static class ComparatorByPhaseCorrelation implements Comparator<PhaseCorrelationPeak2> {
		@Override
		public int compare(PhaseCorrelationPeak2 o1, PhaseCorrelationPeak2 o2) {
			return Double.compare(o1.phaseCorr, o2.phaseCorr);
		}		
	}
	
	public static class ComparatorByCrossCorrelation implements Comparator<PhaseCorrelationPeak2>{
		@Override
		public int compare(PhaseCorrelationPeak2 o1, PhaseCorrelationPeak2 o2) {
			int ccCompare = Double.compare(o1.crossCorr, o2.crossCorr);
			if (ccCompare != 0){
				return ccCompare;
			} else {
				return (int)(o1.nPixel - o2.nPixel);
			}
		}
	}
	
	public <T extends RealType<T>, S extends RealType<S>> void calculateCrossCorr(RandomAccessibleInterval<T> img1, RandomAccessibleInterval<S> img2, 
			long minOverlapPx)
	{
		calculateCrossCorr( img1, img2, minOverlapPx, false );
	}
	
	/*
	 * checks the cross correlation of two images shifted as indicated by this phaseCorrelationPeak,
	 * update the values of crossCor and nPixels accordingly
	 * if the images do not overlap (or overlap less than a specified minimal overlap),
	 * crossCorr and nPixel will be set to 0
	 * @param img1
	 * @param img2
	 * @param minOverlapPx
	 */
	
	public <T extends RealType<T>, S extends RealType<S>> void calculateCrossCorr(RandomAccessibleInterval<T> img1, RandomAccessibleInterval<S> img2, 
			long minOverlapPx, boolean interpolateSubpixel)
	{
		Pair<Interval, Interval> intervals = PhaseCorrelation2Util.getOverlapIntervals(img1, img2, shift);
		
		// no overlap found
		if (intervals == null) {
			crossCorr = Double.NEGATIVE_INFINITY;
			nPixel = 0;
			return;
		}
		
		nPixel = 1;
		for (int i = 0; i< intervals.getA().numDimensions(); i++){
			nPixel *= intervals.getA().dimension(i);
		}
		
		if (nPixel < minOverlapPx){
			crossCorr = Double.NEGATIVE_INFINITY;
			nPixel = 0;
			return;
		}

		// for subpixel move the underlying Img2 by the subpixel offset
		if ( subpixelShift != null && interpolateSubpixel )
		{
			RealRandomAccessible< S > rra = Views.interpolate( Views.extendMirrorSingle( img2 ), new NLinearInterpolatorFactory< S >() );

			InvertibleRealTransform transform = null;

			// e.g. subpixel = (-0.4, 0.1, -0.145)
			final double tx = subpixelShift.getDoublePosition( 0 ) - shift.getDoublePosition( 0 );
			final double ty = subpixelShift.getDoublePosition( 1 ) - shift.getDoublePosition( 1 );

			if ( rra.numDimensions() == 2 )
				transform = new Translation2D( -tx, -ty ); // -relative subpixel shift only
			else if ( rra.numDimensions() == 3 )
				transform = new Translation3D( -tx, -ty, shift.getDoublePosition( 2 ) - subpixelShift.getDoublePosition( 2 ) ); // -relative subpixel shift only

			img2 = Views.interval( Views.raster( RealViews.transform( rra, transform ) ), img2 );
		}

		// calculate cross correlation.
		// note that the overlap we calculate assumes zero-min input
		crossCorr = PhaseCorrelation2Util.getCorrelation(
				Views.zeroMin( Views.interval(Views.zeroMin(img1), intervals.getA())),
				Views.zeroMin( Views.interval(Views.zeroMin(img2), intervals.getB()))
			);
		
	}
	
	/*
	 * calculate cross correlation of two images with no minimal overlap size
	 * @param img1
	 * @param img2
	 */
	public <T extends RealType<T>, S extends RealType<S>> void calculateCrossCorr(RandomAccessibleInterval<T> img1, RandomAccessibleInterval<S> img2){
		calculateCrossCorr(img1, img2, 0);
	}

	/*
	 * refine the shift using subpixel localization in the original PCM
	 * @param pcm
	 */
	public <T extends RealType<T>> void  calculateSubpixelLocalization(RandomAccessibleInterval<T> pcm){
		
		List<Point> peaks = new ArrayList<Point>();
		peaks.add(new Point(pcmLocation));
		
		final int n = pcm.numDimensions();

		boolean[] allowedToMoveInDim = new boolean[ n ];
		for (int i = 0; i< allowedToMoveInDim.length; i++){
			allowedToMoveInDim[i]=false;
		}

		// TODO: It doesnt look like this does anything? Subpixel peaks are just regular peaks as RealPoint - with maxNumMoves == 1 it should now :)
		// subpixel localization can move on periodic condition outofbounds
		List<RefinedPeak<Point>> res = SubpixelLocalization.refinePeaks(peaks, Views.extendPeriodic( pcm ), null, true,
					1, false, 0.0f, allowedToMoveInDim);

		final RefinedPeak< Point > rp = res.get( 0 );
		this.subpixelPcmLocation = new RealPoint( rp );
		/*
		final long[] range = Util.getArrayFromValue( 4l, pcm.numDimensions() );
		final Gradient derivative = new GradientOnDemand< T >( Views.extendPeriodic( pcm ) );
		final ArrayList< long[] > peaksRS = new ArrayList<>();

		final long[] loc = new long[ pcm.numDimensions() ];
		pcmLocation.localize( loc );
		peaksRS.add( loc );

		final ArrayList< Spot > spots = Spot.extractSpots( peaksRS, derivative, range );
		try
		{
			Spot.fitCandidates( spots );
			System.out.println( "rs: " + Util.printCoordinates( spots.get( 0 ) ) );
			//Spot.ransac( spots, 1000, 0.1, 1.0/100.0 );
			//System.out.println( "rsransac: " + Util.printCoordinates( spots.get( 0 ) ) );
			
		} catch ( Exception e )
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		final long[] minSF = new long[ pcm.numDimensions() ];
		final long[] maxSF = new long[ pcm.numDimensions() ];

		pcmLocation.localize( minSF );
		pcmLocation.localize( maxSF );

		for ( int d = 0; d < pcm.numDimensions(); ++d )
		{
			minSF[ d ] -= 2;
			maxSF[ d ] += 2;
		}

		final Cursor< T > cursor = Views.iterable( Views.interval( Views.extendPeriodic( pcm ), minSF, maxSF ) ).localizingCursor();

		double[] sumW = new double[ pcm.numDimensions() ];
		double[] sum = new double[ pcm.numDimensions() ];

		while ( cursor.hasNext() )
		{
			double w = cursor.next().getRealDouble();

			for ( int d = 0; d < pcm.numDimensions(); ++d )
			{
				sumW[ d ] += w;
				sum[ d ] += cursor.getLongPosition( d ) * w;
			}

			//System.out.println( Util.printCoordinates( cursor ) + ": " + w );
		}

		double[] simpleFit = new double[ pcm.numDimensions() ];
		for ( int d = 0; d < pcm.numDimensions(); ++d )
			simpleFit[ d ] = sum[ d ] / sumW[ d ];

		long size = 41;
		Img< FloatType > img = ArrayImgs.floats( Util.getArrayFromValue( size, pcm.numDimensions() ) );
		Cursor< FloatType > c = img.localizingCursor();
		RandomAccess< T > r = Views.extendPeriodic( pcm ).randomAccess();
		long[] pos = new long[ img.numDimensions() ];

		while ( c.hasNext() )
		{
			c.fwd();
			c.localize( pos );
			for ( int d = 0; d < pcm.numDimensions(); ++d )
			{
				// relative to center
				pos[ d ] = size / 2 - pos[ d ];
				pos[ d ] += pcmLocation.getLongPosition( d );
			}
			r.setPosition( pos );
			c.get().set( r.get().getRealFloat() );
		}

		Collection<Localizable> peaksG = new ArrayList<Localizable>();
		peaksG.add( new Point( Util.getArrayFromValue( size/2, pcm.numDimensions() ) ) );

		PeakFitter<FloatType> pf = new PeakFitter<>(img, peaksG,
											new LevenbergMarquardtSolver(), new EllipticGaussianOrtho(),
										new MLEllipticGaussianEstimator( Util.getArrayFromValue( 0.9, pcm.numDimensions() ) ) );
		
		//ImageJFunctions.show( img );
		if ( !pf.process() )
			System.out.println( pf.getErrorMessage() );

		final double[] peakGauss = pf.getResult().values().iterator().next();

		for ( int d = 0; d < pcm.numDimensions(); ++d )
			peakGauss[ d ] = peakGauss[ d ] - size/2 + pcmLocation.getLongPosition( d );

		System.out.println( "gf: " + Util.printCoordinates( peakGauss ) );
		System.out.println( "sf: " + Util.printCoordinates( simpleFit ) );
		System.out.println( "qf: " + Util.printCoordinates( subpixelPcmLocation ) );
		System.out.println();

		//this.subpixelPcmLocation = new RealPoint( spots.get( 0 ) );
		*/

		double maxDist = 0.0;

		for ( int d = 0; d < n; ++d )
			maxDist = Math.max( maxDist, Math.abs( rp.getOriginalPeak().getDoublePosition( d ) - this.subpixelPcmLocation.getDoublePosition( d ) ) );

		//SimpleMultiThreading.threadHaltUnClean();
		// not a stable peak
		if ( maxDist > 0.75 )
		{
			this.subpixelPcmLocation = null;
		}
		else
		{
			this.phaseCorr = rp.getValue();
		}
	}

	public static void main(String[] args) {
		
		double o1 = 6;
		double o2 = Double.NEGATIVE_INFINITY;
		int np1 = 30;
		int np2 = 20;

		System.out.println( Double.isInfinite( o2 ));

		int ccCompare = Double.compare(o1, o2);
		if (ccCompare != 0)
			System.out.println( ccCompare );
		else 
			System.out.println( (int)(np1 - np2) );
		
		System.exit( 0 );
		PhaseCorrelationPeak2 peaks = new PhaseCorrelationPeak2(new Point(new int[] {10, 10}), 1.0);
		Dimensions pcmDims = new FinalDimensions(new int[] {50, 50});
		Dimensions imgDims = new FinalDimensions(new int[] {30, 30});
		PhaseCorrelation2Util.expandPeakToPossibleShifts(peaks, pcmDims, imgDims, imgDims);
		
	}
	

}
