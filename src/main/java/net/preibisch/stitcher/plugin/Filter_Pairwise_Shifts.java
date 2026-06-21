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

import ij.plugin.PlugIn;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.stitcher.gui.popup.SimpleRemoveLinkPopup;

public class Filter_Pairwise_Shifts implements PlugIn
{
	@Override
	public void run(String arg)
	{
		// TODO: should we ask for grouping and then apply filter only to subset?
		final LoadParseQueryXML result = new LoadParseQueryXML();
		if ( !result.queryXML( "for link filtering", false, false, false, false, false ) )
			return;
		final SpimData2 data = result.getData();

		SimpleRemoveLinkPopup.filterPairwiseShifts( data, false, null, null );
		new XmlIoSpimData2().saveWithFilename( data, result.getXMLFileName() );
	}

	public static void main(String[] args)
	{
		new Filter_Pairwise_Shifts().run("");
	}
}
