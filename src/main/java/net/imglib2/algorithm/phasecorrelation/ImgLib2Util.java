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


import ij.ImageJ;
import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;

import java.io.File;
import java.util.ArrayList;

import edu.mines.jtk.dsp.FftComplex;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.fft2.FFTMethods;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

public class ImgLib2Util
{
	public static Img< FloatType > openAs32Bit( final File file )
	{
		return openAs32Bit( file, new ArrayImgFactory< FloatType >() );
	}

	public static Img< FloatType > openAs32Bit( final File file, final ImgFactory< FloatType > factory )
	{
		if ( !file.exists() )
			throw new RuntimeException( "File '" + file.getAbsolutePath() + "' does not exisit." );

		final ImagePlus imp = new Opener().openImage( file.getAbsolutePath() );

		if ( imp == null )
			throw new RuntimeException( "File '" + file.getAbsolutePath() + "' coult not be opened." );

		final Img< FloatType > img;

		if ( imp.getStack().getSize() == 1 )
		{
			// 2d
			img = factory.create( new int[]{ imp.getWidth(), imp.getHeight() }, new FloatType() );
			final ImageProcessor ip = imp.getProcessor();

			final Cursor< FloatType > c = img.localizingCursor();
			
			while ( c.hasNext() )
			{
				c.fwd();

				final int x = c.getIntPosition( 0 );
				final int y = c.getIntPosition( 1 );

				c.get().set( ip.getf( x, y ) );
			}

		}
		else
		{
			// >2d
			img = factory.create( new int[]{ imp.getWidth(), imp.getHeight(), imp.getStack().getSize() }, new FloatType() );

			final Cursor< FloatType > c = img.localizingCursor();

			// for efficiency reasons
			final ArrayList< ImageProcessor > ips = new ArrayList< ImageProcessor >();

			for ( int z = 0; z < imp.getStack().getSize(); ++z )
				ips.add( imp.getStack().getProcessor( z + 1 ) );

			while ( c.hasNext() )
			{
				c.fwd();

				final int x = c.getIntPosition( 0 );
				final int y = c.getIntPosition( 1 );
				final int z = c.getIntPosition( 2 );

				c.get().set( ips.get( z ).getf( x, y ) );
			}
		}

		return img;
	}

	
	public static <T extends RealType<T>, S extends RealType<S>> void copyRealImage(IterableInterval<T> source, RandomAccessibleInterval<S> dest) {
		RandomAccess<S> destRA = dest.randomAccess();
		Cursor<T> srcC = source.cursor();
		
		
		while (srcC.hasNext()){
			srcC.fwd();
			destRA.setPosition(srcC);
			destRA.get().setReal(srcC.get().getRealDouble());
		}
	}
	
	
	public static void main( String[] args )
	{
		new ImageJ();
		
		final Img< FloatType > img = openAs32Bit( new File( "src/main/resources/mri-stack.tif" ) );
		//final Img< FloatType > img = openAs32Bit( new File( "src/main/resources/bridge.png" ) );
		
		ImageJFunctions.show( img );
	}
}
