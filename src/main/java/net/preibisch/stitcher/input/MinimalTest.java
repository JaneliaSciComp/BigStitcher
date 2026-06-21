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
import java.util.Random;

import bdv.BigDataViewer;
import bdv.viewer.ViewerOptions;
import ij.ImageJ;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
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
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;

public class MinimalTest
{
	public static SpimData2 twoAngles()
	{
		final ArrayList< ViewSetup > setups = new ArrayList< ViewSetup >();
		final ArrayList< ViewRegistration > registrations = new ArrayList< ViewRegistration >();

		final Channel c0 = new Channel( 0, "test" );
		final Angle a0 = new Angle( 0 );
		final Angle a1 = new Angle( 1 );
		final Illumination i0 = new Illumination( 0 );

		final Dimensions d0 = new FinalDimensions( 512l, 512l, 86l );
		final VoxelDimensions vd0 = new FinalVoxelDimensions( "px", 0.4566360, 0.4566360, 2.0000000 );

		setups.add( new ViewSetup( 0, "setup 0", d0, vd0, c0, a0, i0 ) );
		setups.add( new ViewSetup( 1, "setup 1", d0, vd0, c0, a1, i0 ) );

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

	public static class MySetupImgLoader implements SetupImgLoader< FloatType >
	{
		final int setupId;

		public MySetupImgLoader( final int setupId )
		{
			this.setupId = setupId;
		}

		@Override
		public RandomAccessibleInterval< FloatType > getImage(
				int timepointId, ImgLoaderHint... hints )
		{
			return getFloatImage( timepointId, false, hints );
		}

		@Override
		public FloatType getImageType() { return new FloatType(); }

		@Override
		public RandomAccessibleInterval< FloatType > getFloatImage(
				int timepointId, boolean normalize, ImgLoaderHint... hints )
		{
			final Random rnd = new Random( setupId );

			final Img< FloatType > img = ArrayImgs.floats( 512, 512, 86 );

			final float scale;
			
			if ( normalize )
				scale = 1;
			else
				scale = 20000;
			
			for ( final FloatType t : img )
				t.set( rnd.nextFloat() * scale);

			return img;
		}

		@Override
		public Dimensions getImageSize( int timepointId ) { return null; }

		@Override
		public VoxelDimensions getVoxelSize( int timepointId ) { return null; }
	}

	public static void main( String[] args )
	{
		new ImageJ();
		SpimData2 spimData = twoAngles();
		
		// crashes for version 2.0.0
		// with version 2.2.0-SNAPSHOT it crashes when calling the brightness dialog, silently
		// doesn't rotate afterwards
		BigDataViewer.open( spimData, "Test 32bit", null, new ViewerOptions() );
	}
}
