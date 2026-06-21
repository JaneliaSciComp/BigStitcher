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
package net.preibisch.stitcher.plugin;

import java.io.File;
import java.util.Map;

import fiji.util.gui.GenericDialogPlus;
import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.Translation3D;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.stitcher.gui.TileConfigurationHelpers;

public class Load_Tile_Configuration implements PlugIn
{
	public static String defaultTCFile = "";

	@Override
	public void run(String arg)
	{
		// load SpimData
		final LoadParseQueryXML result = new LoadParseQueryXML();
		if ( !result.queryXML( "to load a TileConfiguration for", false, false, false, false, false ) )
			return;
		final SpimData2 data = result.getData();

		// ask for parameters
		final GenericDialogPlus gd = new GenericDialogPlus( "" );
		gd.addFileField( "TileConfiguration file", defaultTCFile );
		gd.addCheckbox( "Use_pixel_units", true );
		gd.addCheckbox( "Keep_metadata_rotation", true );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return;

		defaultTCFile = gd.getNextString();
		final boolean pixelUnits = gd.getNextBoolean();
		final boolean metadataRotate = gd.getNextBoolean();

		// apply
		final Map< ViewId, Translation3D > tcParsed = TileConfigurationHelpers.parseTileConfiguration( new File( defaultTCFile ) );
		final Map< ViewId, Translation3D > transformsForData = TileConfigurationHelpers.getTransformsForData( tcParsed, pixelUnits, data );
		TileConfigurationHelpers.applyToData( transformsForData, pixelUnits, metadataRotate, data );

		// save result
		new XmlIoSpimData2().saveWithFilename( data, result.getXMLFileName() );
	}

	public static void main(String[] args)
	{
		new Load_Tile_Configuration().run( "" );
	}

}
