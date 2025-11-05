/*-
 * #%L
 * Multiview stitching of large datasets.
 * %%
 * Copyright (C) 2016 - 2025 Big Stitcher developers.
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
import java.util.HashSet;
import java.util.stream.Collectors;

import ij.plugin.PlugIn;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters.PreAlign;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.algorithm.SpimDataFilteringAndGrouping;
import net.preibisch.stitcher.algorithm.globalopt.GlobalOptStitcher;

public class Global_Optimization_Stitching implements PlugIn
{
	@Override
	public void run(String arg)
	{
		final LoadParseQueryXML result = new LoadParseQueryXML();
		if ( !result.queryXML( "for global optimization", true, true, true, true, true ) )
			return;

		final SpimData2 data = result.getData();
		ArrayList< ViewId > selectedViews = SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() );

		final SpimDataFilteringAndGrouping< SpimData2 > grouping = new SpimDataFilteringAndGrouping<>( data );
		grouping.addFilters( selectedViews.stream().map( vid -> data.getSequenceDescription().getViewDescription( vid ) ).collect( Collectors.toList() ) );

		// Defaults for grouping
		// the default grouping by channels and illuminations
		final HashSet< Class< ? extends Entity > > defaultGroupingFactors = new HashSet<>();
		defaultGroupingFactors.add( Illumination.class );
		defaultGroupingFactors.add( Channel.class );
		// the default comparision by tiles
		final HashSet< Class< ? extends Entity > > defaultComparisonFactors = new HashSet<>();
		defaultComparisonFactors.add( Tile.class );

		GlobalOptimizationParameters params = GlobalOptimizationParameters.askUserForParameters( true, PreAlign.ASK );

		if (params.showExpertGrouping)
			grouping.askUserForGrouping( 
					selectedViews.stream().map( vid -> data.getSequenceDescription().getViewDescription( vid ) ).collect( Collectors.toList() ),
					defaultGroupingFactors,
					defaultComparisonFactors );
		else
		{
			// set defaults
			grouping.addComparisonAxis( Tile.class );
			grouping.addGroupingFactor( Channel.class );
			grouping.addGroupingFactor( Illumination.class );
			grouping.addApplicationAxis( TimePoint.class );
			grouping.addApplicationAxis( Angle.class );
		}

		final ArrayList< Pair< Group< ViewId >, Group< ViewId > > > removedInconsistentPairs = new ArrayList<>();

		if (!GlobalOptStitcher.processGlobalOptimization( data, grouping, params, removedInconsistentPairs, false ))
			return;

		GlobalOptStitcher.removeInconsistentLinks( removedInconsistentPairs, data.getStitchingResults().getPairwiseResults() );

		new XmlIoSpimData2().saveWithFilename( data, result.getXMLFileName() );
	}

	public static void main(String[] args)
	{
		new Global_Optimization_Stitching().run( "" );
	}

}
