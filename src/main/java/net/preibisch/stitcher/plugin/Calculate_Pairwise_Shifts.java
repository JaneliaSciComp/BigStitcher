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

import java.awt.Font;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;

import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Detection;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.TransformationModelGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters.GlobalOptType;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.BasicRegistrationParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.BasicRegistrationParameters.InterestPointOverlapType;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.GroupParameters.InterestpointGroupingType;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.resave.ProgressWriterIJ;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseStitchingResult;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximalGroupOverlap;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.PairwiseSetup;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.algorithm.PairwiseStitchingParameters;
import net.preibisch.stitcher.algorithm.SpimDataFilteringAndGrouping;
import net.preibisch.stitcher.algorithm.globalopt.TransformationTools;
import net.preibisch.stitcher.algorithm.lucaskanade.LucasKanadeParameters;
import net.preibisch.stitcher.algorithm.lucaskanade.LucasKanadeParameters.WarpFunctionType;
import net.preibisch.stitcher.gui.StitchingUIHelper;

public class Calculate_Pairwise_Shifts implements PlugIn
{

	private final static String[] methodChoices = {
			"Phase Correlation",
			"Lucas-Kanade",
			"Interest-Point Registration (with existing Interest Points)",
			"Interest-Point Registration (with new Interest Points)"};

	private static boolean expertGrouping;
	private static boolean expertAlgorithmParameters;
	private static int defaultMethodIdx = 0;

	@Override
	public void run(String arg)
	{
		final LoadParseQueryXML result = new LoadParseQueryXML();
		if ( !result.queryXML( "for pairwise shift calculation", true, true, true, true, true ) )
			return;

		final SpimData2 data = result.getData();
		ArrayList< ViewId > selectedViews = SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() );

		final SpimDataFilteringAndGrouping< SpimData2 > grouping = new SpimDataFilteringAndGrouping<>( data );
		grouping.addFilters( selectedViews.stream().map( vid -> data.getSequenceDescription().getViewDescription( vid ) ).collect( Collectors.toList() ) );
		final boolean is2d = StitchingUIHelper.allViews2D( grouping.getFilteredViews() );

		// ask for method and expert grouping/parameters
		GenericDialog gd = new GenericDialog( "How to calculate pairwise registrations" );
		gd.addChoice( "method", methodChoices, methodChoices[defaultMethodIdx] );
		gd.addCheckbox( "show_expert_grouping_options", expertGrouping );
		gd.addCheckbox( "show_expert_algorithm_parameters", expertAlgorithmParameters );

		gd.showDialog();
		if(gd.wasCanceled())
			return;

		defaultMethodIdx = gd.getNextChoiceIndex();
		expertGrouping = gd.getNextBoolean();
		expertAlgorithmParameters = gd.getNextBoolean();

		// Defaults for grouping
		// the default grouping by channels and illuminations
		final HashSet< Class <? extends Entity> > defaultGroupingFactors = new HashSet<>();
		defaultGroupingFactors.add( Illumination.class );
		defaultGroupingFactors.add( Channel.class );
		// the default comparision by tiles
		final HashSet< Class <? extends Entity> > defaultComparisonFactors = new HashSet<>();
		defaultComparisonFactors.add(Tile.class);
		// the default application along time points and angles
		final HashSet< Class <? extends Entity> > defaultApplicationFactors = new HashSet<>();
		defaultApplicationFactors.add( TimePoint.class );
		defaultApplicationFactors.add( Angle.class );

		if (expertGrouping)
			grouping.askUserForGrouping(data.getSequenceDescription().getViewDescriptions().values(), defaultGroupingFactors, defaultComparisonFactors);
		else
		{
			grouping.getAxesOfApplication().addAll( defaultApplicationFactors );
			grouping.getGroupingFactors().addAll( defaultGroupingFactors );
			grouping.getAxesOfComparison().addAll( defaultComparisonFactors );
		}

		if (defaultMethodIdx >= 2)
		{
			if (!processInterestPoint( data, grouping, defaultMethodIdx == 2 ))
				return;
		}
		else
		{
			grouping.askUserForGroupingAggregator();
			final long[] ds = StitchingUIHelper.askForDownsampling( data, is2d );

			if (defaultMethodIdx == 0) // Phase Correlation
			{
				PairwiseStitchingParameters params = expertAlgorithmParameters ? PairwiseStitchingParameters.askUserForParameters() : new PairwiseStitchingParameters();
				if (!processPhaseCorrelation( data, grouping, params, ds ))
					return;
			}
			else if (defaultMethodIdx == 1) // Lucas-Kanade
			{
				LucasKanadeParameters params = expertAlgorithmParameters ? LucasKanadeParameters.askUserForParameters() : new LucasKanadeParameters( WarpFunctionType.TRANSLATION );
				if (!processLucasKanade( data, grouping, params, ds ))
					return;
			}
		}

		// update XML
		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Saving XML ... " );
		new XmlIoSpimData2().saveWithFilename( data, result.getXMLFileName() );
	}
	
	public static void main(String[] args)
	{
		new Calculate_Pairwise_Shifts().run( "Test ..." );
	}
	
	public static boolean processPhaseCorrelation(
			SpimData2 data,
			SpimDataFilteringAndGrouping< SpimData2 > filteringAndGrouping,
			PairwiseStitchingParameters params,
			long[] dsFactors)
	{
		// getpairs to compare
		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Finding pairs to compute overlap ... " );

		List< ? extends Pair< ? extends Group< ? extends ViewId >, ? extends Group< ? extends ViewId > > > pairs =  filteringAndGrouping.getComparisons();

		// calculate
		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Computing overlap ... " );
		final ArrayList< PairwiseStitchingResult< ViewId > > results = TransformationTools.computePairs(
				(List< Pair< Group< ViewId >, Group< ViewId > > >) pairs, params, filteringAndGrouping.getSpimData().getViewRegistrations(), 
				filteringAndGrouping.getSpimData().getSequenceDescription(), filteringAndGrouping.getGroupedViewAggregator(),
				dsFactors );

		// remove old results

		// this is just a cast of pairs to Group<ViewId>
		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Organizing resuls ... " );

		final List< ValuePair< Group< ViewId >, Group< ViewId > > > castPairs = pairs.stream().map( p -> {
			final Group< ViewId > vidGroupA = new Group<>( p.getA().getViews().stream().map( v -> (ViewId) v ).collect( Collectors.toSet() ) );
			final Group< ViewId > vidGroupB = new Group<>( p.getB().getViews().stream().map( v -> (ViewId) v ).collect( Collectors.toSet() ) );
			return new ValuePair<>( vidGroupA, vidGroupB );
		}).collect( Collectors.toList() );

		for (ValuePair< Group< ViewId >, Group< ViewId > > pair : castPairs)
		{
			// try to remove a -> b and b -> a, just to make sure
			data.getStitchingResults().getPairwiseResults().remove( pair );
			data.getStitchingResults().getPairwiseResults().remove( new ValuePair<>( pair.getB(), pair.getA() ) );
		}

		// update StitchingResults with Results
		for ( final PairwiseStitchingResult< ViewId > psr : results )
		{
			if (psr == null)
				continue;

			data.getStitchingResults().setPairwiseResultForPair(psr.pair(), psr );
		}

		return true;
	}

	public static boolean processLucasKanade(
			SpimData2 data,
			SpimDataFilteringAndGrouping< SpimData2 > filteringAndGrouping,
			LucasKanadeParameters params,
			long[] dsFactors)
	{
		// getpairs to compare
		List< ? extends Pair< ? extends Group< ? extends ViewId >, ? extends Group< ? extends ViewId > > > pairs = filteringAndGrouping
				.getComparisons();

		// calculate
		final ArrayList< PairwiseStitchingResult< ViewId > > results = TransformationTools.computePairsLK(
				(List< Pair< Group< ViewId >, Group< ViewId > > >) pairs,
				params,
				filteringAndGrouping.getSpimData().getViewRegistrations(),
				filteringAndGrouping.getSpimData().getSequenceDescription(),
				filteringAndGrouping.getGroupedViewAggregator(),
				dsFactors,
				new ProgressWriterIJ());

		// remove old results
		// this is just a cast of pairs to Group<ViewId>
		final List< ValuePair< Group< ViewId >, Group< ViewId > > > castPairs = pairs.stream().map( p -> {
			final Group< ViewId > vidGroupA = new Group<>(
					p.getA().getViews().stream().map( v -> (ViewId) v ).collect( Collectors.toSet() ) );
			final Group< ViewId > vidGroupB = new Group<>(
					p.getB().getViews().stream().map( v -> (ViewId) v ).collect( Collectors.toSet() ) );
			return new ValuePair<>( vidGroupA, vidGroupB );
		} ).collect( Collectors.toList() );

		for ( ValuePair< Group< ViewId >, Group< ViewId > > pair : castPairs )
		{
			// try to remove a -> b and b -> a, just to make sure
			data.getStitchingResults().getPairwiseResults().remove( pair );
			data.getStitchingResults().getPairwiseResults().remove( new ValuePair<>( pair.getB(), pair.getA() ) );
		}

		// update StitchingResults with Results
		for ( final PairwiseStitchingResult< ViewId > psr : results )
		{
			if ( psr == null )
				continue;

			data.getStitchingResults().setPairwiseResultForPair( psr.pair(), psr );
		}

		return true;
	}

	// TODO: remove interest points from BigStitcher entirely (except for ICP refinement)
	public static boolean processInterestPoint(final SpimData2 data,
			final SpimDataFilteringAndGrouping< SpimData2 > filteringAndGrouping,
			boolean existingInterestPoints)
	{

		// detect new interest points if requested
		if (!existingInterestPoints)
		{
			// by default the registration suggests what is selected in the dialog
			Interest_Point_Detection.defaultGroupTiles = filteringAndGrouping.getGroupingFactors().contains( Tile.class );
			Interest_Point_Detection.defaultGroupIllums = filteringAndGrouping.getGroupingFactors().contains( Illumination.class );
			new Interest_Point_Detection().detectInterestPoints( data, filteringAndGrouping.getFilteredViews() );
		}

		// by default the registration suggests what is selected in the dialog
		// (and was passed to filteringAndGrouping)
		Interest_Point_Registration.defaultGroupTiles = filteringAndGrouping.getGroupingFactors()
				.contains( Tile.class );
		Interest_Point_Registration.defaultGroupIllums = filteringAndGrouping.getGroupingFactors()
				.contains( Illumination.class );
		Interest_Point_Registration.defaultGroupChannels = filteringAndGrouping.getGroupingFactors()
				.contains( Channel.class );

		// which timepoints are part of the data (we dont necessarily need them,
		// but basicRegistrationParameters GUI wants them)
		List< ? extends ViewId > viewIds = filteringAndGrouping.getFilteredViews();
		final List< TimePoint > timepointToProcess = SpimData2.getAllTimePointsSorted( data, viewIds );
		final int nAllTimepoints = data.getSequenceDescription().getTimePoints().size();

		// query basic registration parameters
		final BasicRegistrationParameters brp = new Interest_Point_Registration().basicRegistrationParameters(
				timepointToProcess, nAllTimepoints, data, (List< ViewId >) viewIds );
		if ( brp == null )
			return false;

		// query algorithm parameters
		GenericDialog gd = new GenericDialog( "Registration Parameters" );

		gd.addMessage( "Algorithm parameters [" + brp.pwr.getDescription() + "]",
				new Font( Font.SANS_SERIF, Font.BOLD, 12 ) );
		gd.addMessage( "" );

		//brp.pwr.presetTransformationModel( new TransformationModelGUI( 0 ) );
		brp.pwr.addQuery( gd );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return false;
		if ( !brp.pwr.parseDialog( gd ) )
			return false;

		// get all possible group pairs
		List< ? extends Pair< ? extends Group< ? extends ViewId >, ? extends Group< ? extends ViewId > > > pairs = filteringAndGrouping
				.getComparisons();

		// remove old results
		// this is just a cast of pairs to Group<ViewId>
		final List< ValuePair< Group< ViewId >, Group< ViewId > > > castPairs = pairs.stream().map( p -> {
			final Group< ViewId > vidGroupA = new Group<>(
					p.getA().getViews().stream().map( v -> (ViewId) v ).collect( Collectors.toSet() ) );
			final Group< ViewId > vidGroupB = new Group<>(
					p.getB().getViews().stream().map( v -> (ViewId) v ).collect( Collectors.toSet() ) );
			return new ValuePair<>( vidGroupA, vidGroupB );
		} ).collect( Collectors.toList() );

		for ( ValuePair< Group< ViewId >, Group< ViewId > > pair : castPairs )
		{
			// try to remove a -> b and b -> a, just to make sure
			data.getStitchingResults().getPairwiseResults().remove( pair );
			data.getStitchingResults().getPairwiseResults().remove( new ValuePair<>( pair.getB(), pair.getA() ) );
		}

		// remove non-overlapping comparisons
		final List< Pair< Group< ViewId >, Group< ViewId > > > removedPairs = TransformationTools
				.filterNonOverlappingPairs( (List< Pair< Group< ViewId >, Group< ViewId > > >) pairs,
						data.getViewRegistrations(), data.getSequenceDescription() );
		removedPairs
				.forEach( p -> System.out.println( "Skipping non-overlapping pair: " + p.getA() + " -> " + p.getB() ) );

		for ( final Pair< Group< ViewId >, Group< ViewId > > pair : (List< Pair< Group< ViewId >, Group< ViewId > > >) pairs )
		{
			// all views in group pair
			final HashSet< ViewId > vids = new HashSet< ViewId >();
			vids.addAll( pair.getA().getViews() );
			vids.addAll( pair.getB().getViews() );

			// simple PairwiseSetup with just two groups (fully connected to
			// each other)
			Set<Group<ViewId>> groups = new HashSet<>();
			groups.add( pair.getA() );
			groups.add( pair.getB() );
			final PairwiseSetup< ViewId > setup = new PairwiseSetup< ViewId >( new ArrayList<>( vids ), groups )
			{

				@Override
				protected List< Pair< ViewId, ViewId > > definePairsAbstract()
				{
					// all possible links between groups
					final List< Pair< ViewId, ViewId > > res = new ArrayList<>();
					for ( final ViewId vidA : pair.getA() )
						for ( final ViewId vidB : pair.getB() )
							res.add( new ValuePair< ViewId, ViewId >( vidA, vidB ) );
					return res;
				}

				@Override
				public List< ViewId > getDefaultFixedViews()
				{
					// first group will remain fixed -> we get the transform to
					// align second group to this target
					return new ArrayList<>( pair.getA().getViews() );
				}
			};

			// prepare setup
			setup.definePairs();
			setup.detectSubsets();

			// get copies of view registrations (as they will be modified) and
			// interest points
			final Map< ViewId, ViewRegistration > registrationMap = new HashMap<>();
			final Map< ViewId, ViewInterestPointLists > ipMap = new HashMap<>();
			for ( ViewId vid : vids )
			{
				final ViewRegistration vrOld = data.getViewRegistrations().getViewRegistration( vid );
				final ViewInterestPointLists iplOld = data.getViewInterestPoints().getViewInterestPointLists( vid );
				registrationMap.put( vid, new ViewRegistration( vid.getTimePointId(), vid.getViewSetupId(),
						new ArrayList<>( vrOld.getTransformList() ) ) );
				ipMap.put( vid, iplOld );
			}

			final Interest_Point_Registration reg = new Interest_Point_Registration();
			// run the registration for this pair, skip saving results if it did not work
			if ( !reg.processRegistration(
					setup,
					data.getSequenceDescription().getViewSetups(),
					brp.pwr,
					InterestpointGroupingType.ADD_ALL,
					InterestPointOverlapType.ALL,
					0.0,
					pair.getA().getViews(),
					null,
					null,
					registrationMap,
					data.getSequenceDescription().getViewDescriptions(),
					ipMap,
					brp.labelMap,
					new GlobalOptimizationParameters(Double.MAX_VALUE, Double.MAX_VALUE, GlobalOptType.ONE_ROUND_SIMPLE, true, false ),
					false, //matchacrosslabels
					true ) )
				continue;

			// get newest Transformation of groupB (the accumulative transform
			// determined by registration)
			final ViewTransform vtB = registrationMap.get( pair.getB().iterator().next() ).getTransformList().get( 0 );

			List< Pair< Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > > stats = reg.getStatistics();

			// TODO: is this correct?
			// since the grouped IP are split up again in the statistics, can we just sum inliers & candidates
			// to get the total cands/inliers. or are we counting some twice?
			double candidates = 0;
			double inliers = 0;
			for (final Pair< Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > stat : stats)
			{
				candidates += stat.getB().getCandidates().size();
				inliers += stat.getB().getInliers().size();
			}


			final AffineTransform3D result = new AffineTransform3D();
			result.set( vtB.asAffine3D().getRowPackedCopy() );
			IOFunctions.println( "resulting transformation: " + Util.printCoordinates( result.getRowPackedCopy() ) );

			// get Overlap Bounding Box, which we need for stitching results
			final List< List< ViewId > > groupListsForOverlap = new ArrayList<>();
			groupListsForOverlap.add( new ArrayList<>( pair.getA().getViews() ) );
			groupListsForOverlap.add( new ArrayList<>( pair.getB().getViews() ) );
			BoundingBoxMaximalGroupOverlap< ViewId > bbDet = new BoundingBoxMaximalGroupOverlap< ViewId >(
					groupListsForOverlap, data.getSequenceDescription(), data.getViewRegistrations() );
			BoundingBox bbOverlap = bbDet.estimate( "Max Overlap" );

			final double oldTransformHash = PairwiseStitchingResult.calculateHash(
					data.getViewRegistrations().getViewRegistration( pair.getA().getViews().iterator().next() ),
					data.getViewRegistrations().getViewRegistration( pair.getA().getViews().iterator().next() ) );
			data.getStitchingResults().getPairwiseResults().put( pair,
					new PairwiseStitchingResult<>( pair, bbOverlap, result, inliers/candidates, oldTransformHash ) );
		}

		return true;
	}

}
