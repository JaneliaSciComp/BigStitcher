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
package net.preibisch.stitcher.gui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import bdv.BigDataViewer;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.state.SourceState;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.datasetmanager.grid.RegularTranformHelpers;


public class TileConfigurationHelpers
{

	private static int minNumLines = 10;

	public static Map< Pair< File, Integer >, Translation3D > parseTileConfigurationOld(final File tcFile)
	{
		final Map< Pair< File, Integer >, Translation3D > res = new HashMap<>();

		final File parentDir = tcFile.getParentFile();

		try
		{
			final BufferedReader reader = new BufferedReader( new FileReader( tcFile ) );
			int dims = -1;
			boolean multiseries = false;
			boolean absolutePath = false;

			while ( reader.ready() )
			{
				String nextLine = reader.readLine().trim();
				nextLine = nextLine.split( "#" )[0].trim();

				if ( nextLine.length() < 3 )
					continue;

				// read dim=n header
				if ( nextLine.startsWith( "dim" ) )
				{
					List< String > splitLine = Arrays.asList( nextLine.split( "=" ) );
					if ( splitLine.size() != 2 )
					{
						reader.close();
						return null; // warn here?
					}
					dims = Integer.parseInt( splitLine.get( 1 ).trim() );
				}
				// read multiseries=true header
				else if ( nextLine.startsWith( "multiseries" ) )
				{
					String entries[] = nextLine.split( "=" );
					if ( entries.length != 2 )
					{
						reader.close();
						return null;
					}

					if ( entries[1].trim().equals( "true" ) )
					{
						multiseries = true;
					}
				}
				// read absolutepath=true header
				else if ( nextLine.startsWith( "absolutepath" ) )
				{
					String entries[] = nextLine.split( "=" );
					if ( entries.length != 2 )
					{
						reader.close();
						return null;
					}

					if ( entries[1].trim().equals( "true" ) )
					{
						absolutePath = true;
					}
				}
				else
				{
					List< String > splitLine = Arrays.asList( nextLine.split( ";" ) );
					if ( splitLine.size() != 3 )
					{
						reader.close();
						return null; // warn here?
					}

					String imageName = splitLine.get( 0 ).trim();
					if ( imageName.length() == 0 )
					{
						reader.close();
						return null;
					}

					int seriesNr = -1;
					if ( multiseries )
					{
						String imageSeries = splitLine.get( 1 ).trim(); // sub-volume (series nr)
						if ( imageSeries.length() == 0 )
						{
							{
								reader.close();
								return null;
							}
						}
						else
						{
							try
							{
								seriesNr = Integer.parseInt( imageSeries );
							}
							catch ( NumberFormatException e )
							{
								reader.close();
								return null;
							}
						}
					}

					String loc = splitLine.get( 2 ).trim();
					if ( !loc.startsWith( "(" ) || !loc.endsWith( ")" ) )
					{
						reader.close();
						return null; // warn here?
					}
					loc = loc.substring( 1, loc.length() - 1 );

					List< String > locs = Arrays.asList( loc.split( "," ) );

					if ( locs.size() != dims || dims > 3)
					{
						reader.close();
						return null; // warn here?
					}

					double[] tr = new double[3];
					for ( int i = 0; i < dims; i++ )
					{
						tr[i] = Double.parseDouble( locs.get( i ).trim() );
					}

					File imageFile = absolutePath ? new File(imageName) : new File(parentDir.getAbsolutePath(), imageName);
					res.put( new ValuePair<>( imageFile, seriesNr ), new Translation3D( tr ) );
				}

			}
			reader.close();
		}
		catch ( IOException | NumberFormatException e )
		{
			e.printStackTrace();
			return null;
		}

		return res;
	}

	/**
	 * read new style tile configuration (lines of the form: vs_id;tp_id;(x_0, x_1,...))
	 * @param tcFile the tile configuration file
	 * @return map from viewIds (NB: tpid may be -1, in that case, this should apply to any view with the same setupid (wildcard)), null on errors
	 */
	public static Map< ViewId, Translation3D > parseTileConfiguration(final File tcFile)
	{

		final Map< ViewId, Translation3D > res = new HashMap<>();

		try
		{
			final BufferedReader reader = new BufferedReader( new FileReader( tcFile ) );
			int dims = -1;

			while ( reader.ready() )
			{
				String nextLine = reader.readLine().trim();
				nextLine = nextLine.split( "#" )[0].trim();

				if ( nextLine.length() < 3 )
					continue;

				// reader dim=n header
				if ( nextLine.startsWith( "dim" ) )
				{
					List< String > splitLine = Arrays.asList( nextLine.split( "=" ) );
					if ( splitLine.size() != 2 )
					{
						reader.close();
						return null; // warn here?
					}
					dims = Integer.parseInt( splitLine.get( 1 ).trim() );
				}
				else
				{
					List< String > splitLine = Arrays.asList( nextLine.split( ";" ) );
					if ( splitLine.size() != 3 )
					{
						reader.close();
						return null; // warn here?
					}
					int vsId = Integer.parseInt( splitLine.get( 0 ).trim() );
					int tpId = -1;
					// set tpId to -1 if no timepoint was specified
					try {
						tpId = Integer.parseInt( splitLine.get( 1 ).trim() );
					} catch (NumberFormatException e) {}

					String loc = splitLine.get( 2 ).trim();
					if ( !loc.startsWith( "(" ) || !loc.endsWith( ")" ) )
					{
						reader.close();
						return null; // warn here?
					}
					loc = loc.substring( 1, loc.length() - 1 );

					List< String > locs = Arrays.asList( loc.split( "," ) );

					if ( locs.size() != dims )
					{
						reader.close();
						return null; // warn here?
					}

					double[] tr = new double[3];
					for ( int i = 0; i < dims; i++ )
					{
						tr[i] = Double.parseDouble( locs.get( i ).trim() );
					}

					res.put( new ViewId( tpId, vsId ), new Translation3D( tr ) );
				}

			}
			reader.close();
		}
		catch ( IOException e )
		{
			e.printStackTrace();
			return null;
		}

		return res;
	}

	/*
	 * expand transformations to the actual views in SpimData.
	 * NB: input with tpid = -1 will be applied to every present time point
	 */
	public static Map<ViewId, Translation3D> getTransformsForData(Map<ViewId, Translation3D> locations, boolean pixelUnits, AbstractSpimData< ? > data )
	{
		final Map<ViewId, Translation3D> res = new HashMap<>();
		final Set< ViewId > vidsWithTransformations = locations.keySet();
		final Collection< BasicViewDescription< ? > > vds = (Collection< BasicViewDescription< ? > >) data.getSequenceDescription().getViewDescriptions().values();

		for ( BasicViewDescription< ? > vd : vds )
		{
			ViewId key;
			if (vidsWithTransformations.contains( vd ))
				key = vd;
			else if (vidsWithTransformations.contains( new ViewId(-1, vd.getViewSetupId()) ))
				key = new ViewId(-1, vd.getViewSetupId());
			else
				continue;

			final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( vd );
			final AffineTransform3D calib = new AffineTransform3D();
			calib.set( vr.getTransformList().get( vr.getTransformList().size() - 1 ).asAffine3D().getRowPackedCopy() );

			final VoxelDimensions voxelDims = vd.getViewSetup().getVoxelSize();

			final Translation3D translation3d = locations.get( key );
			final double[] translationVec = translation3d.getTranslationCopy();

			if (!pixelUnits)
				for (int d = 0; d<voxelDims.numDimensions(); d++)
					translationVec[d] /= voxelDims.dimension( d );

			for (int d = 0; d<calib.numDimensions(); d++)
				translationVec[d] *= calib.get( d, d );

			res.put( new ViewId(vd.getTimePointId(), vd.getViewSetupId()), new Translation3D( translationVec ) );
		}
		return res;
	}

	/*
	 * apply transformations from Tile configuration to SpimData
	 */
	public static void applyToData(Map<ViewId, Translation3D> locations, boolean pixelUnits, boolean keepRotation,
			AbstractSpimData< ? > data)
	{
		if (data == null)
			return;
		final Map< ViewId, Translation3D > transformsForData = getTransformsForData( locations, pixelUnits, data );
		final Collection< BasicViewDescription< ? > > vds = (Collection< BasicViewDescription< ? > >) data.getSequenceDescription().getViewDescriptions().values();

		for ( BasicViewDescription< ? > vd : vds )
		{
			if (!vd.isPresent())
				continue;

			if (!transformsForData.containsKey( vd ))
				continue;

			final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( vd );

			final ViewTransform vtCalib = vr.getTransformList().get( vr.getTransformList().size() - 1 );
			final AffineTransform3D calib = new AffineTransform3D();
			calib.set( vr.getTransformList().get( vr.getTransformList().size() - 1 ).asAffine3D().getRowPackedCopy() );
	
			vr.getTransformList().clear();
			vr.preconcatenateTransform( vtCalib );

			final AffineTransform3D tr = new AffineTransform3D();
			tr.set( transformsForData.get( vd ).getRowPackedCopy() );
			ViewTransformAffine vtTC = new ViewTransformAffine( "Translation from Tile Configuration", tr );
			vr.preconcatenateTransform( vtTC );

			if (keepRotation)
			{
				AffineTransform3D rotation = new AffineTransform3D();
				Pair< Double, Integer > rotAngleAndAxis = RegularTranformHelpers.getRoatationFromMetadata( vd.getViewSetup().getAttribute( Angle.class ) );
				if (rotAngleAndAxis != null)
				{
					rotation.rotate( rotAngleAndAxis.getB(), rotAngleAndAxis.getA() );
					vr.preconcatenateTransform( new ViewTransformAffine( "Rotation from Metadata", rotation.copy() ));
				}
			}
			vr.updateModel();
		}
	}

	/*
	 * update BDV with parsed TileConfiguration
	 */
	public static void updateBDVPreview(Map<ViewId, Translation3D> locations, boolean pixelUnits, boolean keepRotation,
			AbstractSpimData< ? > data, BigDataViewer bdv)
	{
		if (data == null || bdv == null )
			return;

		final Map< ViewId, Translation3D > transformsForData = getTransformsForData( locations, pixelUnits, data );
		final Collection< BasicViewDescription< ? > > vds = (Collection< BasicViewDescription< ? > >) data.getSequenceDescription().getViewDescriptions().values();
		final int currentTPId = data.getSequenceDescription().getTimePoints().getTimePointsOrdered()
				.get( bdv.getViewer().getState().getCurrentTimepoint() ).getId();
		for ( BasicViewDescription< ? > vd : vds )
		{
			if (vd.getTimePointId() != currentTPId)
				continue;

			final int sourceIdx = StitchingExplorerPanel.getBDVSourceIndex( vd.getViewSetup(), data );
			final SourceState< ? > s = bdv.getViewer().getState().getSources().get( sourceIdx );

			final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( vd );
			final AffineTransform3D inv = vr.getModel().copy().inverse();
			
			final AffineTransform3D calib = new AffineTransform3D();
			calib.set( vr.getTransformList().get( vr.getTransformList().size() - 1 ).asAffine3D().getRowPackedCopy() );
	
			AffineTransform3D transform;
			if (transformsForData.containsKey( vd ))
			{
				transform  = inv.copy().preConcatenate( calib ).preConcatenate( transformsForData.get( vd ) );
			}
			else
				continue;

			if (keepRotation)
			{
				AffineTransform3D rotation = new AffineTransform3D();
				Pair< Double, Integer > rotAngleAndAxis = RegularTranformHelpers.getRoatationFromMetadata( vd.getViewSetup().getAttribute( Angle.class ) );
				if (rotAngleAndAxis != null)
				{
					rotation.rotate( rotAngleAndAxis.getB(), rotAngleAndAxis.getA() );
					transform.preConcatenate( rotation.copy() );
				}
			}
	
			( (TransformedSource< ? >) s.getSpimSource() ).setFixedTransform( transform );
		}
	
		bdv.getViewer().requestRepaint();
	}

	/*
	 * get representation of parsed tc as HTML string
	 */
	@Deprecated
	public static String previewLocations(Map<ViewId, Translation3D> locations, boolean pixelUnits, AbstractSpimData< ? > data)
	{
		if (data == null )
			return "";
		if (locations == null)
			return "<html><h2> View Locations </h2><p style=\"color:red\">WARNING: could not read tile configuration.</p></html>";

		final Map< ViewId, Translation3D > transformsForData = getTransformsForData( locations, pixelUnits, data );

		final StringBuilder sb = new StringBuilder();
		sb.append("<html><h2> View Locations </h2>");
		transformsForData.forEach( (vid, tr) -> {
			sb.append( "<br /> ViewSetup " + vid.getViewSetupId() + ", TP " + vid.getTimePointId() + ": " );

			// locations : round to 3 decimal places
			DecimalFormat df = new DecimalFormat( "#.###" );
			df.setRoundingMode( RoundingMode.HALF_UP );
			sb.append( df.format( tr.get( 0, 3 ) ) );
			sb.append( ", " );
			sb.append( df.format( tr.get( 1, 3 ) ) );
			sb.append( ", " );
			sb.append( df.format( tr.get( 2, 3 ) ) );
		} );

		// pad the label a little
		for (int i = 0; i < minNumLines - transformsForData.size(); i++)
			sb.append( "<br />"  );
		sb.append( "</html>" );
		return sb.toString();
	}

	public static void main(String[] args) throws Exception
	{
		System.out.println( "== Old style:" );
		Map< Pair< File, Integer >, Translation3D > res = parseTileConfigurationOld(
				new File( "/Users/david/Desktop/tileConfigOld.txt" ) );

		if ( res != null )
			res.entrySet().forEach( e -> {
				System.out.println( e.getKey().getA() + ", series " + e.getKey().getB() + ": "
						+ Util.printCoordinates( e.getValue().getTranslationCopy() ) );
			} );

		System.out.println( "== New style:" );
		Map< ViewId, Translation3D > res2 = parseTileConfiguration(
				new File( "/Users/david/Desktop/tileConfig.txt" ) );

		if ( res2 != null )
			res2.entrySet().forEach( e -> {
				System.out.println( "View: " + e.getKey().getViewSetupId() + ", TP: " + e.getKey().getTimePointId()
						+ ": " + Util.printCoordinates( e.getValue().getTranslationCopy() ) );
			} );
	}
}
