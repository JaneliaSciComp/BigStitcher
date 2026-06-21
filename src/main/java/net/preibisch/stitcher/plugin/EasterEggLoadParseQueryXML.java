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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;

import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader;
import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader2;
import net.preibisch.stitcher.input.FractalSpimDataGenerator;
import net.preibisch.mvrecon.fiji.plugin.queryXML.GenericLoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;

public class EasterEggLoadParseQueryXML extends LoadParseQueryXML
{
	SpimData2 virtual;
	boolean isEasterEgg;

	@Override
	protected boolean tryParsing( final URI xmlURI, final boolean parseAllTypes )
	{
		final String input = xmlURI.toString().toLowerCase().trim();

		if ( input.equals( "define" ) )
		{
			this.message1 = "This will define a new dataset, press OK to continue";
			this.message2 = "";
			this.color = GUIHelper.good;

			this.data = null;
			this.attributes = null;

			return true;
		}
		else if ( input.equals( "fractal" ) )
		{
			this.data = FractalSpimDataGenerator.createVirtualSpimData();
			this.attributes = getAttributes( data, comparator );

			final ArrayList< HashSet< Integer > > numEntitiesPerAttrib = entitiesPerAttribute();
			populateAttributesEntities( attributes.size(), numEntitiesPerAttrib );

			this.message1 = "This is a simulated fractal for testing.";
			this.message2 = GenericLoadParseQueryXML.getSpimDataDescription( data, attributes, numEntitiesPerAttrib, attributes.size() );
			this.color = GUIHelper.good;

			return true;
		}
		else if ( input.equals( "genericbeads" ) )
		{
			this.data = SpimData2.convert( SimulatedBeadsImgLoader2.createSpimDataFromUserInput() );
			this.attributes = getAttributes( data, comparator );

			final ArrayList< HashSet< Integer > > numEntitiesPerAttrib = entitiesPerAttribute();
			populateAttributesEntities( attributes.size(), numEntitiesPerAttrib );

			this.message1 = "These are simulated beads for testing.";
			this.message2 = GenericLoadParseQueryXML.getSpimDataDescription( data, attributes, numEntitiesPerAttrib, attributes.size() );
			this.color = GUIHelper.good;

			return true;
		}
		else if ( input.equals( "beads" ) )
		{
			this.data = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample() );
			this.attributes = getAttributes( data, comparator );

			final ArrayList< HashSet< Integer > > numEntitiesPerAttrib = entitiesPerAttribute();
			populateAttributesEntities( attributes.size(), numEntitiesPerAttrib );

			this.message1 = "These are simulated beads for testing.";
			this.message2 = GenericLoadParseQueryXML.getSpimDataDescription( data, attributes, numEntitiesPerAttrib, attributes.size() );
			this.color = GUIHelper.good;

			return true;
		}
		else if ( input.equals( "easter" ) )
		{
			this.message1 = "Following options exists:";
			this.message2 = "fractal, beads, genericbeads";

			this.color = GUIHelper.warning;
			return false;
		}
		else
		{
			this.message2 = "To access simulated datasets type 'fractal' or 'beads'; and now with cloud support (AWS & Google Cloud)";
			return super.tryParsing( xmlURI, parseAllTypes );
		}
	}
}
