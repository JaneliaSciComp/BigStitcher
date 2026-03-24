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
package net.preibisch.stitcher.algorithm.lucaskanade;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import Jama.Matrix;

import ij.ImageJ;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.preibisch.mvrecon.process.phasecorrelation.ImgLib2Util;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.realtransform.TranslationGet;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximalGroupOverlap;
import net.preibisch.mvrecon.process.downsampling.Downsample;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.stitcher.algorithm.TransformTools;

public class RigidWarp implements WarpFunction
{
	private final WarpFunction concreteInstance;
	
	public RigidWarp(int n)
	{
		if (n == 1)
			concreteInstance = new TranslationWarp( 1 );
		else if (n==2)
			concreteInstance = new RigidWarp2D();
		else if (n==3)
			concreteInstance = new RigidWarp3D();
		else
			throw new IllegalArgumentException( "Can only use rigid model for <=3 dimensions." );
	}
	
	@Override
	public int numParameters()
	{
		return concreteInstance.numParameters();
	}

	@Override
	public double partial(RealLocalizable pos, int d, int param)
	{
		return concreteInstance.partial( pos, d, param );
	}

	@Override
	public AffineGet getAffine(double[] p)
	{
		return concreteInstance.getAffine( p );
	}

	public static void main(String[] args)
	{
		RandomAccessibleInterval< FloatType > a = ImgLib2Util.openAs32Bit( new File( "73.tif.zip" ) );
		RandomAccessibleInterval< FloatType > b = ImgLib2Util.openAs32Bit( new File( "74.tif.zip" ) );
		
		long slice = 40;
		ImageJFunctions.show( a );
		
		a = Views.zeroMin( Views.hyperSlice( a, 2, slice ));
		b = Views.zeroMin( Views.hyperSlice( b, 2, slice ));

		TranslationGet t1 = new Translation2D();
		TranslationGet t2 = new Translation2D(460, 0);
		ArrayList< Pair< RealInterval, AffineGet > > views = new ArrayList<Pair<RealInterval, AffineGet>>();
		views.add( new ValuePair< RealInterval, AffineGet >( a, t1 ) );
		views.add( new ValuePair< RealInterval, AffineGet >( b, t2 ) );

		RealInterval overlap = BoundingBoxMaximalGroupOverlap.getMinBoundingIntervalSingle( views );

		final RealInterval transformed1 = TransformTools.applyTranslation( a, t1, new boolean[] {false, false} );
		final RealInterval transformed2 = TransformTools.applyTranslation( b, t2, new boolean[] {false, false} );

		// get overlap in images' coordinates
		final RealInterval localOverlap1 = TransformTools.getLocalOverlap( transformed1, overlap );
		final RealInterval localOverlap2 = TransformTools.getLocalOverlap( transformed2, overlap );

		// round to integer interval
		final Interval interval1 = TransformTools.getLocalRasterOverlap( localOverlap1 );
		final Interval interval2 = TransformTools.getLocalRasterOverlap( localOverlap2 );

		//final WarpFunction warp = new TranslationWarp(3);
		final WarpFunction warp = new RigidWarp(2);
		//final WarpFunction warp = new AffineWarp( 3 );

		// rotate second image
		AffineTransform2D rot = new AffineTransform2D();
		rot.rotate( 1.4 * Math.PI / 180 );
		RandomAccessibleInterval< FloatType > rotated = Views.interval(
				RealViews.affine( 
						Views.interpolate( Views.extendMirrorSingle( Views.zeroMin( Views.interval( b, interval2 ) ) ), new NLinearInterpolatorFactory<>() ),
						rot.copy() ),
				interval2);

		// show input
		new ImageJ();
		ImageJFunctions.show( Views.interval( a,  interval1 ) );
		ImageJFunctions.show( rotated );

		final ExecutorService service = Executors.newFixedThreadPool( Threads.numThreads() );

		// downsample input
		RandomAccessibleInterval<FloatType> simple2x1 = Downsample.simple2x(
				Views.zeroMin(Views.interval(a, interval1)),
				new boolean[] { false, false } );
		RandomAccessibleInterval<FloatType> simple2x2 = Downsample.simple2x(
				Views.zeroMin(Views.interval(rotated, interval2)),
				new boolean[] { false, false } );

		// align

		//Align< FloatType > lk = new Align<>( Views.zeroMin( Views.interval( a, interval1 ) ), new ArrayImgFactory<>(), warp );
		Align< FloatType > lk = new Align<>( simple2x1, new ArrayImgFactory<>( new FloatType() ), warp );
		//System.out.println( Util.printCoordinates( lk.align( Views.zeroMin( Views.interval( b, interval2 ) ), 100, 0.01 ).getRowPackedCopy() ) );
		//final AffineTransform transform = lk.align( Views.zeroMin( rotated ), 100, 0.01 );
		final AffineTransform transform = lk.align( simple2x2, 100, 0.1, service );

		service.shutdown();

		// transformation matrix
		System.out.println( Util.printCoordinates( transform.getRowPackedCopy() ) );

		// correct input and show
		RandomAccessibleInterval< FloatType > backRotated = Views.interval(
				RealViews.affine( 
						Views.interpolate( Views.extendMirrorSingle( Views.zeroMin( Views.interval( b, interval2 ) ) ), new NLinearInterpolatorFactory<>() ),
						rot.copy().preConcatenate( transform ).copy() ),
				interval2);

		ImageJFunctions.show( backRotated );

		// constructor needs column packed matrix, therefore the transpose
		Matrix mt = new Matrix( transform.getRowPackedCopy(), 3).transpose();
		Matrix rigid = mt.getMatrix( 0, 1, 0, 1 );

		// check whether result is rotation matrix (det == +-1, orthogonal)
		System.out.println( rigid.det() );
		System.out.println( Util.printCoordinates( rigid.times( rigid.transpose() ).getRowPackedCopy() ) );
	}
}
