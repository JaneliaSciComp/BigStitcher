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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;

import ij.ImagePlus;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.generic.sequence.ImgLoaderHints;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;

public class GenerateSpimData
{
	public static SpimData2 grid3x2()
	{
		final ArrayList< ViewSetup > setups = new ArrayList< ViewSetup >();
		final ArrayList< ViewRegistration > registrations = new ArrayList< ViewRegistration >();

		final Channel c0 = new Channel( 0, "RFP" );
		final Channel c1 = new Channel( 1, "YFP" );
		final Channel c2 = new Channel( 2, "GFP" );

		final Angle a0 = new Angle( 0 );
		final Illumination i0 = new Illumination( 0 );

		final Tile t0 = new Tile( 0, "Tile0", new double[]{ 0.0, 0.0, 0.0 } );
		final Tile t1 = new Tile( 1, "Tile1", new double[]{ 450.0, 0.0, 0.0 } );
		final Tile t2 = new Tile( 2, "Tile2", new double[]{ 0.0, 450.0, 0.0 } );
		final Tile t3 = new Tile( 3, "Tile3", new double[]{ 450.0, 450.0, 0.0 } );

		final Dimensions d0 = new FinalDimensions( 512l, 512l, 86l );
		final VoxelDimensions vd0 = new FinalVoxelDimensions( "px", 0.4566360, 0.4566360, 2.0000000 );

		setups.add( new ViewSetup( 0, "setup 0", d0, vd0, t0, c0, a0, i0 ) );
		setups.add( new ViewSetup( 1, "setup 1", d0, vd0, t1, c0, a0, i0 ) );
		setups.add( new ViewSetup( 2, "setup 2", d0, vd0, t2, c0, a0, i0 ) );
		setups.add( new ViewSetup( 3, "setup 3", d0, vd0, t3, c0, a0, i0 ) );

		setups.add( new ViewSetup( 4, "setup 4", d0, vd0, t0, c1, a0, i0 ) );
		setups.add( new ViewSetup( 5, "setup 5", d0, vd0, t1, c1, a0, i0 ) );
		setups.add( new ViewSetup( 6, "setup 6", d0, vd0, t2, c1, a0, i0 ) );
		setups.add( new ViewSetup( 7, "setup 7", d0, vd0, t3, c1, a0, i0 ) );

		setups.add( new ViewSetup( 8, "setup 8", d0, vd0, t0, c2, a0, i0 ) );
		setups.add( new ViewSetup( 9, "setup 9", d0, vd0, t1, c2, a0, i0 ) );
		setups.add( new ViewSetup( 10, "setup 10", d0, vd0, t2, c2, a0, i0 ) );
		setups.add( new ViewSetup( 11, "setup 11", d0, vd0, t3, c2, a0, i0 ) );

		final ArrayList< TimePoint > t = new ArrayList< TimePoint >();
		t.add( new TimePoint( 0 ) );
		final TimePoints timepoints = new TimePoints( t );

		final ArrayList< ViewId > missing = new ArrayList< ViewId >();
		final MissingViews missingViews = new MissingViews( missing );

		final ImgLoader imgLoader = new ImgLoader()
		{
			@Override
			public SetupImgLoader< ? > getSetupImgLoader( int setupId )
			{
				return new MySetupImgLoader( setupId );
			}
		};

		for ( final ViewSetup vs : setups )
		{
			final ViewRegistration vr = new ViewRegistration( t.get( 0 ).getId(), vs.getId() );

			final Tile tile = vs.getTile();

			final double minResolution = Math.min( Math.min( vs.getVoxelSize().dimension( 0 ), vs.getVoxelSize().dimension( 1 ) ), vs.getVoxelSize().dimension( 2 ) );
			
			final double calX = vs.getVoxelSize().dimension( 0 ) / minResolution;
			final double calY = vs.getVoxelSize().dimension( 1 ) / minResolution;
			final double calZ = vs.getVoxelSize().dimension( 2 ) / minResolution;

			final AffineTransform3D m = new AffineTransform3D();
			m.set( calX, 0.0f, 0.0f, 0.0f, 
				   0.0f, calY, 0.0f, 0.0f,
				   0.0f, 0.0f, calZ, 0.0f );
			final ViewTransform vt = new ViewTransformAffine( "Calibration", m );
			vr.preconcatenateTransform( vt );

			final AffineTransform3D translation = new AffineTransform3D();

			if ( tile.hasLocation() )
			{
				translation.set( tile.getLocation()[ 0 ] / calX, 0, 3 );
				translation.set( tile.getLocation()[ 1 ] / calY, 1, 3 );
				translation.set( tile.getLocation()[ 2 ] / calZ, 2, 3 );
			}

			vr.preconcatenateTransform( new ViewTransformAffine( "Translation", translation ) );

			vr.updateModel();		
			
			registrations.add( vr );
		}

		final SequenceDescription sd = new SequenceDescription( timepoints, setups, imgLoader, missingViews );
		final SpimData2 data = new SpimData2(
				URI.create( "/" ),
				sd,
				new ViewRegistrations( registrations ),
				new ViewInterestPoints(),
				new BoundingBoxes(),
				new PointSpreadFunctions(),
				new StitchingResults(),
				new IntensityAdjustments() );

		return data;
	}

	final static HashMap< String, ImagePlus > openImgs = new HashMap<>();

	public static class MySetupImgLoader implements SetupImgLoader< UnsignedShortType >
	{
		final int setupId;

		public MySetupImgLoader( final int setupId )
		{
			this.setupId = setupId;
		}

		@Override
		public RandomAccessibleInterval< UnsignedShortType > getImage(
				int timepointId, ImgLoaderHint... hints )
		{
			ImagePlus imp;
			String file;
			
			if ( setupId % 4 == 0 )
				file = "73.tif.zip";
			else if ( setupId % 4 == 1 )
				file = "74.tif.zip";
			else if ( setupId % 4 == 2 )
				file = "75.tif.zip";
			else 
				file = "76.tif.zip";

			if ( openImgs.containsKey( file ) )
			{
				imp = openImgs.get( file );
			}
			else
			{
				imp = new ImagePlus( file );
				openImgs.put( file, imp );
			}

			Img< UnsignedShortType > img = copyChannelUSST( imp, setupId / 4 );

			return img;
		}

		@Override
		public UnsignedShortType getImageType() { return new UnsignedShortType(); }

		@Override
		public RandomAccessibleInterval< FloatType > getFloatImage(
				int timepointId, boolean normalize, ImgLoaderHint... hints )
		{
			ImagePlus imp;
			String file;
			
			if ( setupId % 4 == 0 )
				file = "73.tif.zip";
			else if ( setupId % 4 == 1 )
				file = "74.tif.zip";
			else if ( setupId % 4 == 2 )
				file = "75.tif.zip";
			else 
				file = "76.tif.zip";

			if ( openImgs.containsKey( file ) )
			{
				imp = openImgs.get( file );
			}
			else
			{
				imp = new ImagePlus( file );
				openImgs.put( file, imp );
			}

			Img< FloatType > img = copyChannel( imp, setupId / 4 );

			imp.close();

			return img;
		}

		@Override
		public Dimensions getImageSize( int timepointId ) { return null; }

		@Override
		public VoxelDimensions getVoxelSize( int timepointId ) { return null; }
	}

	public static Img< UnsignedShortType > copyChannelUSST( final ImagePlus imp, final int channel )
	{
		final int w, h, d;

		Img< UnsignedShortType > img = ArrayImgs.unsignedShorts( w = imp.getWidth(), h = imp.getHeight(), d = imp.getNSlices() );
		
		final Cursor< UnsignedShortType > c = img.cursor();

		for ( int z = 0; z < d; ++z )
		{
			final int[] pixels = (int[])imp.getStack().getProcessor( z + 1 ).getPixels();
			
			for ( int i = 0; i < w*h; ++i )
			{
				if ( channel == 0 )
					c.next().set( ( pixels[ i ] & 0xff0000) >> 16 );
				else if ( channel == 1 )
					c.next().set( ( pixels[ i ] & 0xff00 ) >> 8 );
				else
					c.next().set( pixels[ i ] & 0xff );
			}
		}
		
		return img;
	}

	public static Img< FloatType > copyChannel( final ImagePlus imp, final int channel )
	{
		final int w, h, d;

		Img< FloatType > img = ArrayImgs.floats( w = imp.getWidth(), h = imp.getHeight(), d = imp.getNSlices() );
		
		final Cursor< FloatType > c = img.cursor();

		for ( int z = 0; z < d; ++z )
		{
			final int[] pixels = (int[])imp.getStack().getProcessor( z + 1 ).getPixels();
			
			for ( int i = 0; i < w*h; ++i )
			{
				if ( channel == 0 )
					c.next().set( ( pixels[ i ] & 0xff0000) >> 16 );
				else if ( channel == 1 )
					c.next().set( ( pixels[ i ] & 0xff00 ) >> 8 );
				else
					c.next().set( pixels[ i ] & 0xff );
			}
		}
		
		return img;
	}

	public static void main( String[] args )
	{
		SpimData2 spimData = grid3x2();
		SequenceDescription sd = spimData.getSequenceDescription();
		ImgLoader i = sd.getImgLoader();

		TimePoint firstTp = sd.getTimePoints().getTimePointsOrdered().get( 0 );
		int tpId = firstTp.getId();

		for ( final ViewSetup vs: spimData.getSequenceDescription().getViewSetups().values() )
		{
			SetupImgLoader< ? > sil = i.getSetupImgLoader( vs.getId() );
			ViewDescription vd = sd.getViewDescription( tpId, vs.getId() );
			
			Tile t = vd.getViewSetup().getTile();

			if ( t.hasLocation() )
				System.out.println( "Loading: " + t.getName() + " " + Util.printCoordinates( t.getLocation() ) + " " + vd.getViewSetup().getChannel().getName() );
			else
				System.out.println( "Loading: " + t.getName() + " (unknown location) " + vd.getViewSetup().getChannel().getName() );
			
			ImageJFunctions.show( (RandomAccessibleInterval< UnsignedShortType >)sil.getImage( tpId, ImgLoaderHints.LOAD_COMPLETELY ) ).resetDisplayRange();
		}
	}
}
