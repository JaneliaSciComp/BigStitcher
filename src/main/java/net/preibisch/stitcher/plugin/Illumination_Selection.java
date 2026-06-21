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

import java.util.ArrayList;
import java.util.stream.Collectors;

import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.stitcher.gui.popup.SelectIlluminationPopup;

public class Illumination_Selection implements PlugIn
{

	@Override
	public void run(String arg)
	{
		final LoadParseQueryXML result = new LoadParseQueryXML();
		if ( !result.queryXML( "for illumination Selection", false, false, false, false, false ) )
			return;

		final SpimData2 data = result.getData();
		ArrayList< ViewId > selectedViews = SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() );

		SpimData2 filteredSpimData = SelectIlluminationPopup.processIlluminationSelection( 
				data, 
				selectedViews.stream().map( vid -> data.getSequenceDescription().getViewDescription( vid ) ).collect( Collectors.toList() ),
				false,
				false,
				null );

		if (filteredSpimData != null)
			new XmlIoSpimData2().saveWithFilename( filteredSpimData, result.getXMLFileName() );
	}


	public static void main(String[] args)
	{
		new Illumination_Selection().run( "" );
	}

}
