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
package net.preibisch.stitcher.headless;

import java.io.File;
import java.io.PrintWriter;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.realtransform.Translation;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.io.TextFileAccess;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.downsampling.Downsample;
import net.preibisch.simulation.SimulateTileStitching;
import net.preibisch.stitcher.algorithm.PairwiseStitching;
import net.preibisch.stitcher.algorithm.PairwiseStitchingParameters;

public class StitchingPairwise
{
	public static void main( String[] args )
	{
		//new ImageJ();

		final double minOverlap = 0.85;

		final Thread[] threads = new Thread[ 14 ];

		for ( int i = 0; i < 14; ++i )
		{
			final int snrInt;
			final boolean subpixel;
			if ( i >= 7 )
			{
				snrInt = Util.pow( 2, i - 7 );
				subpixel = true;
			}
			else
			{
				snrInt = Util.pow( 2, i );
				subpixel = false;
			}

			System.out.println( i + " " + snrInt + " " + subpixel );

			threads[ i ] = new Thread( new Runnable()
			{
				public void run()
				{
					final float snr = snrInt;
			
					final SimulateTileStitching sts = new SimulateTileStitching( new Random( 123432 ), true, Util.getArrayFromValue( minOverlap, 3 ), DeconViews.createExecutorService() );
			
					final PairwiseStitchingParameters params = new PairwiseStitchingParameters( 0.1, 5, subpixel, subpixel, false );
					final ExecutorService service = DeconViews.createExecutorService();
			
					final Random rnd = new Random( 34 );
			
					for ( int downsample = 8; downsample >= 1; downsample /= 2 )
					{
						final PrintWriter out;
						if ( subpixel )
							out = TextFileAccess.openFileWrite( new File( "subpixel_snr_" + Math.round( snr ) + "ds" + downsample + ".txt" ) );
						else
							out = TextFileAccess.openFileWrite( new File( "nosubpixel_snr_" + Math.round( snr ) + "ds" + downsample + ".txt" ) );
						
						int[] ds = new int[ 3 ];
						ds[ 0 ] = downsample;
						ds[ 1 ] = downsample;
						ds[ 2 ] = Math.max( 1, downsample / 2 );
			
						IOFunctions.println( "------------------------------" );
						IOFunctions.println( "subpixel:" + subpixel + ", SNR: " + snr + ", downsample: " + downsample + " ["  + Util.printCoordinates( ds ) + "]" );
						IOFunctions.println( "------------------------------" );
			
						final int numTests = 500;
			
						long time;
			
						for ( int i = 0; i < numTests; ++i )
						{
							time = System.currentTimeMillis();
			
							final double[] ov = new double[ 3 ];
							for ( int d = 0; d < ov.length; ++d )
								ov[ d ] = minOverlap + (rnd.nextDouble() / 10.0);
			
							//System.out.println( Util.printCoordinates( ov ) );
							sts.init( ov, rnd.nextBoolean() );
			
							final double[] correct = sts.getCorrectTranslation();
							//System.out.println( "Known shift (right relative to left): " + Util.printCoordinates( correct ) );
			
							final Pair< Img< FloatType >, Img< FloatType > > pair = sts.getNextPair( snr );
			
							final RandomAccessibleInterval< FloatType > img1 = downsample( pair.getA(), ds );
							final RandomAccessibleInterval< FloatType > img2 = downsample( pair.getB(), ds );
			
							//SimulateTileStitching.show( img1, "1" );
							//SimulateTileStitching.show( img2, "2" );
							//SimpleMultiThreading.threadHaltUnClean();
			
							final Translation3D t1 = new Translation3D( 0, 0, 0 );
							final Translation3D t2 = new Translation3D( 0, 0, 0 );
					
							final Pair< Translation, Double > r = PairwiseStitching.getShift( img1, img2, t1, t2, params, service );
				
							double[] shift = r.getA().getTranslationCopy();
							
							for ( int d = 0; d < correct.length; ++d )
								shift[ d ] *= ds[ d ];
			
							for ( int d = 0; d < correct.length; ++d )
								shift[ d ] -= correct[ d ];
			
							double d = dist( shift );
			
							time = System.currentTimeMillis() - time;
			
							IOFunctions.println( time + "ms, " + shift[ 0 ] + " " + shift[ 1 ]  + " " + shift[ 2 ] + " " + d + " " + r.getB() );
							out.println( shift[ 0 ] + "\t" + shift[ 1 ]  + "\t" + shift[ 2 ] + "\t" + d + "\t" + r.getB() );
							out.flush();
						}
			
						out.close();
					}
				}
			} );
		}

		for ( int ithread = 0; ithread < threads.length; ++ithread )
			threads[ ithread ].start();

		try
		{
			for ( int ithread = 0; ithread < threads.length; ++ithread )
				threads[ ithread ].join();
		}
		catch ( InterruptedException ie ) { throw new RuntimeException(ie); }
	}

	public static RandomAccessibleInterval< FloatType > downsample( RandomAccessibleInterval< FloatType > input, final int[] downsample )
	{
		int dsx = downsample[ 0 ];
		int dsy = downsample[ 1 ];
		int dsz = downsample[ 2 ];

		final ExecutorService service = Executors.newFixedThreadPool( Threads.numThreads() );

		while ( dsx > 1 || dsy > 1 || dsz > 1 )
		{
			if ( dsy > 1 )
			{
				input = Downsample.simple2x( input, new boolean[]{ false, true, false } );
				dsy /= 2;
			}

			if ( dsx > 1 )
			{
				input = Downsample.simple2x( input, new boolean[]{ true, false, false } );
				dsx /= 2;
			}


			if ( dsz > 1 )
			{
				input = Downsample.simple2x( input, new boolean[]{ false, false, true } );
				dsz /= 2;
			}
		}

		service.shutdown();
		/*
		for ( ;dsx > 1; dsx /= 2 )
			input = Downsample.simple2x( input, new ArrayImgFactory<>(), new boolean[]{ true, false, false } );

		for ( ;dsy > 1; dsy /= 2 )
			input = Downsample.simple2x( input, new ArrayImgFactory<>(), new boolean[]{ false, true, false } );

		for ( ;dsz > 1; dsz /= 2 )
			input = Downsample.simple2x( input, new ArrayImgFactory<>(), new boolean[]{ false, false, true } );
		*/
		return input;
	}

	public static double dist( final double[] lengths )
	{
		double l = 0;

		for ( final double d : lengths )
			l += d*d;

		return Math.sqrt( l );
	}
}
