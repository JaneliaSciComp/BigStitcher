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

import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.stitcher.process.ICPRefinement;
import net.preibisch.stitcher.process.ICPRefinement.ICPRefinementParameters;
import net.preibisch.stitcher.process.ICPRefinement.ICPType;

public class ICP_Refine implements PlugIn
{
	@Override
	public void run(String arg)
	{
		final LoadParseQueryXML result = new LoadParseQueryXML();
		if ( !result.queryXML( "for ICP refinement", true, true, true, true, true ) )
			return;

		final SpimData2 data = result.getData();
		ArrayList< ViewId > selectedViews = SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() );

		final ICPRefinementParameters params = ICPRefinement.initICPRefinement( data, selectedViews.stream().map( vid -> data.getSequenceDescription().getViewDescription( vid ) ).collect( Collectors.toList() ) );

		if ( params == null )
			return;

		final GenericDialog gd = new GenericDialog( "ICP Refinement" );

		gd.addChoice( "ICP_Refinement_Type", ICPRefinement.refinementType, ICPRefinement.refinementType[ ICPRefinement.defaultRefinementChoice ] );

		GlobalOptimizationParameters.addSimpleParametersToDialog( gd );

		gd.addMessage( "" );
		gd.addMessage( "The following parameters are ignored if EXPERT is selected above", GUIHelper.mediumstatusfont );
		gd.addMessage( "" );

		gd.addChoice( "Downsampling", ICPRefinement.downsampling, ICPRefinement.downsampling[ ICPRefinement.defaultDownsamplingChoice ] );
		gd.addChoice( "Interest Point threshold", ICPRefinement.threshold, ICPRefinement.threshold[ ICPRefinement.defaultThresholdChoice ] );
		gd.addChoice( "ICP_Max_Error", ICPRefinement.distance, ICPRefinement.distance[ ICPRefinement.defaultDistanceChoice ] );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		final ICPType icpType = ICPType.values()[ ICPRefinement.defaultRefinementChoice = gd.getNextChoiceIndex() ];
		final GlobalOptimizationParameters globalOptParams = GlobalOptimizationParameters.parseSimpleParametersFromDialog( gd );

		if ( icpType == ICPType.Expert )
		{
			if ( !ICPRefinement.getGUIParametersAdvanced( data, params ) )
				return;
		}
		else
		{
			final int downsamplingChoice = ICPRefinement.defaultDownsamplingChoice = gd.getNextChoiceIndex();
			final int thresholdChoice = ICPRefinement.defaultThresholdChoice = gd.getNextChoiceIndex();
			final int distanceChoice = ICPRefinement.defaultDistanceChoice = gd.getNextChoiceIndex();

			if ( !ICPRefinement.getGUIParametersSimple( icpType, data, params, downsamplingChoice, thresholdChoice, distanceChoice ) )
				return;
		}

		ICPRefinement.refine( data, params, globalOptParams, null );

		new XmlIoSpimData2().saveWithFilename( data, result.getXMLFileName() );
	}


	public static void main(String[] args)
	{
		BigStitcher.setupTesting();
		new ICP_Refine().run( "" );
	}

}
