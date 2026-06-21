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
import java.util.List;

import bdv.BigDataViewer;
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
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.realtransform.TranslationGet;
import net.imglib2.type.numeric.complex.ComplexDoubleType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.stitcher.algorithm.AveragedRandomAccessible;


public class FractalSpimDataGenerator
{
	private int numDimensions;
	private AveragedRandomAccessible< LongType > fractalsRA;

	public FractalSpimDataGenerator(int numD){
		fractalsRA = new AveragedRandomAccessible<>( numD );
		this.numDimensions = numD;		
	}

	public static SpimData2 createVirtualSpimData()
	{
		// shift and scale the fractal
		final AffineTransform3D m = new AffineTransform3D();
		double scale = 200;
		m.set( scale, 0.0f, 0.0f, 0.0f, 
			   0.0f, scale, 0.0f, 0.0f,
			   0.0f, 0.0f, scale, 0.0f);
		
		final AffineTransform3D mShift = new AffineTransform3D();
		double shift = 100;
		mShift.set( 1.0f, 0.0f, 0.0f, shift, 
					0.0f, 1.0f, 0.0f, shift,
					0.0f, 0.0f, 1.0f, shift
					);
		final AffineTransform3D mShift2 = new AffineTransform3D();
		double shift2x = 1200;
		double shift2y = 300;
		mShift2.set( 1.0f, 0.0f, 0.0f, shift2x, 
					0.0f, 1.0f, 0.0f, shift2y,
					0.0f, 0.0f, 1.0f, 0.0f
					);
		
		final AffineTransform3D mShift3 = new AffineTransform3D();
		double shift3x = 500;
		double shift3y = 1300;
		mShift3.set( 1.0f, 0.0f, 0.0f, shift3x, 
					0.0f, 1.0f, 0.0f, shift3y,
					0.0f, 0.0f, 1.0f, 0.0f
					);
		
		
		AffineTransform3D m2 = m.copy();
		AffineTransform3D m3 = m.copy();
		m.preConcatenate( mShift );
		m2.preConcatenate( mShift2 );
		m3.preConcatenate( mShift3 );
		
		final int tilesX = 7;
		final int tilesY = 6;
		m.preConcatenate( new Translation3D( -300, 0, 0 ) );

		final float correctOverlap = 0.2f;
		final float wrongOverlap = 0.3f;

		Interval start = new FinalInterval( new long[] {-399,-399,0},  new long[] {0, 0, 0});
		List<Interval> intervals = FractalSpimDataGenerator.generateTileList( 
				start, tilesX, tilesY, correctOverlap );
		
		List<RealLocalizable> falseStarts = FractalSpimDataGenerator.getTileMins(
														FractalSpimDataGenerator.generateTileList( start, tilesX, tilesY, wrongOverlap ));
		
		FractalSpimDataGenerator fsdg = new FractalSpimDataGenerator( 3 );
		fsdg.addFractal( m );
		fsdg.addFractal( m2 );
		fsdg.addFractal( m3 );
		
		return fsdg.generateSpimData( intervals , falseStarts);
	}

	public void addFractal(AffineGet transform)
	{
		JuliaRealRandomAccessible fractalRA = new JuliaRealRandomAccessible(new ComplexDoubleType( -0.4, 0.6 ), 300, 300, numDimensions);
		fractalsRA.addRAble(Views.raster( RealViews.affineReal( fractalRA, transform )));
	}

	/**
	 * @param start the first interval
	 * @param n number of tiles in x
	 * @param m number of tiles in y
	 * @param overlap overlap e (0-1)
	 * @return intervals
	 */
	public static List<Interval> generateTileList(Interval start, int n, int m, double overlap)
	{
		List<Interval> res = new ArrayList<>();
		for (int x = 0; x < n; ++x){
			for (int y = 0; y < m; ++y){
				
				Interval tInterval = new FinalInterval( start );
				tInterval = Intervals.translate( tInterval, (long) ( x * (1 - overlap) * start.dimension( 0 ) ), 0 );
				tInterval = Intervals.translate( tInterval, (long) ( y * (1 - overlap) * start.dimension( 1 ) ), 1 );
				res.add( tInterval );
			}
		}
		return res;
	}
	
	public static List< RealLocalizable > getTileMins(List<Interval> intervals)
	{
		final List<RealLocalizable> mins = new ArrayList<>();
		for(Interval iv : intervals)
		{
			RealPoint min = new RealPoint( iv.numDimensions() );
			iv.min( min );
			mins.add( min );
		}
		return mins;
	}
	
	public static List<TranslationGet> getTileTranslations(List<Interval> intervals)
	{
		final List< TranslationGet > tr = new ArrayList<>();
		for(Interval iv : intervals)
		{
			double[] min = new double[iv.numDimensions()];
			iv.realMin( min );

			if (iv.numDimensions() == 2)
				tr.add( new Translation2D( min ) );
			else
				tr.add( new Translation3D( min ) );

		}
		return tr;		
		
	}
	
	/**
	 * create SpimData containing Views at each Interval
	 * @param intervals list of intervals
	 * @return generated SpimData
	 */
	public SpimData2 generateSpimData(final List<Interval> intervals)
	{
		final List<RealLocalizable> mins = new ArrayList<>();
		for(Interval iv : intervals)
		{
			RealPoint min = new RealPoint( iv.numDimensions() );
			iv.min( min );
			mins.add( min );
		}
		return generateSpimData( intervals, mins );
	}
	
	/**
	 * create SpimData containing Views at each Interval, set the initial Registration for each to mins
	 * @param intervals list of source intervals
	 * @param mins potentially wrong list of minima for each interval
	 * @return generated SpimData
	 */
	public SpimData2 generateSpimData(final List<Interval> intervals, final List<RealLocalizable> mins)
	{
		final ArrayList< ViewSetup > setups = new ArrayList< ViewSetup >();
		final ArrayList< ViewRegistration > registrations = new ArrayList< ViewRegistration >();

		final Channel c0 = new Channel( 0 );
		final Angle a0 = new Angle( 0 );
		final Illumination i0 = new Illumination( 0 , "Illum 2");
		final Illumination i1 = new Illumination( 1 , "Illum 1");
		
		final Dimensions d0 = intervals.get(0);
		final VoxelDimensions vd0 = new FinalVoxelDimensions("px", 1.0, 1.0, 1.0);
		
		for (int i = 0; i < intervals.size(); ++i)
		{
			double[] pos = new double[intervals.get( 0 ).numDimensions()];
			mins.get( i ).localize( pos );
			final Tile t = new Tile( i, "Tile " + i, pos );
			setups.add( new ViewSetup( 2*i, "setup " + 2*i, d0, vd0, t, c0, a0, i0 ) );
			setups.add( new ViewSetup( 2*i + 1, "setup " + (2*i + 1), d0, vd0, t, c0, a0, i1 ) );
		}

		final ArrayList< TimePoint > t = new ArrayList< TimePoint >();
		t.add( new TimePoint( 0 ) );
		final TimePoints timepoints = new TimePoints( t );

		final ArrayList< ViewId > missing = new ArrayList< ViewId >();
		final MissingViews missingViews = new MissingViews( missing );

		final ImgLoader imgLoader = new FractalImgLoader( intervals, vd0, fractalsRA );

		for ( final ViewSetup vs : setups )
		{
			final ViewRegistration vr = new ViewRegistration( t.get( 0 ).getId(), vs.getId() );

			final Tile tile = vs.getTile();

			final double minResolution = Math.min( Math.min( vs.getVoxelSize().dimension( 0 ), vs.getVoxelSize().dimension( 1 ) ), vs.getVoxelSize().dimension( 2 ) );

			final double calX = vs.getVoxelSize().dimension( 0 ) / minResolution;
			final double calY = vs.getVoxelSize().dimension( 1 ) / minResolution;
			final double calZ = numDimensions > 2 ? vs.getVoxelSize().dimension( 2 ) / minResolution : 1.0;

			final AffineTransform3D translation = new AffineTransform3D();

			if ( tile.hasLocation() )
			{
				translation.set( tile.getLocation()[ 0 ] * calX, 0, 3 );
				translation.set( tile.getLocation()[ 1 ] * calY, 1, 3 );
				if (numDimensions > 2) translation.set( tile.getLocation()[ 2 ] * calZ, 2, 3 );
			}

			vr.concatenateTransform( new ViewTransformAffine( "Translation", translation ) );

			final AffineTransform3D m = new AffineTransform3D();
			m.set( calX, 0.0f, 0.0f, 0.0f, 
				   0.0f, calY, 0.0f, 0.0f,
				   0.0f, 0.0f, calZ, 0.0f );
			final ViewTransform vt = new ViewTransformAffine( "Calibration", m );
			vr.concatenateTransform( vt );

			vr.updateModel();
			
			registrations.add( vr );
		}

		final SequenceDescription sd = new SequenceDescription( timepoints, setups, imgLoader, missingViews );
		final SpimData2 data = 
				new SpimData2(
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
	
	public static void main(String[] args)
	{
		Interval start = new FinalInterval( new long[] {0,0,0},  new long[] {100, 100, 1});
		List<Interval> res = generateTileList( start, 3, 3, 0.2 );
		for (Interval i : res){
			System.out.println("(" + Long.toString( i.min( 0 )) + "," + Long.toString( i.min( 1 )) + ")");
		}
		
		final AffineTransform3D m = new AffineTransform3D();
		double scale = 300;
		m.set( scale, 0.0f, 0.0f, 0.0f, 
			   0.0f, scale, 0.0f, 0.0f,
			   0.0f, 0.0f, scale, 0.0f );
		
		FractalSpimDataGenerator fsdg = new FractalSpimDataGenerator(3);
		fsdg.addFractal( m );
		
		BigDataViewer.open(  fsdg.generateSpimData( res ), "", null, null );
		
		/*
		new ImageJ();
		RandomAccessibleInterval< LongType > rai = new FractalSpimDataGenerator( new AffineTransform2D() ).getImage( res.get( 0 ) );
		ImageJFunctions.show( rai );
		*/
	}
}
