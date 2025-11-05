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
package net.preibisch.stitcher.process;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ij.IJ;
import ij.gui.GenericDialog;
import mpicbg.models.AbstractModel;
import mpicbg.models.Affine3D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.InterpolatedAffineModel3D;
import mpicbg.models.Model;
import mpicbg.models.RigidModel3D;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Detection;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.TransformationModelGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters.GlobalOptType;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.AdvancedRegistrationParameters;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseLinkImpl;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoG;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGParameters;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOpt;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOptIterative;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOptTwoRound;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.ConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.SimpleIterativeConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.linkremoval.MaxErrorLinkRemoval;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.strong.InterestPointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.weak.MetaDataWeakLinkFactory;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwiseTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.AllToAll;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.PairwiseSetup;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.Subset;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.InterestPointGrouping;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.InterestPointGroupingMinDistance;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSACParameters;
import net.preibisch.stitcher.gui.StitchingUIHelper;
import net.preibisch.stitcher.gui.overlay.DemoLinkOverlay;
import net.preibisch.stitcher.gui.popup.RefineWithICPPopup;

public class ICPRefinement
{
	public static enum ICPType{ TileRefine, ChromaticAbberation, All, Expert }
	public static String[] refinementType = new String[]{ "Simple (tile registration)", "Simple (chromatic abberation)", "Simple (all together)", "Expert ..." };
	public static int defaultRefinementChoice = 0;

	public static String[] downsampling = new String[]{ "Downsampling 2/2/1", "Downsampling 4/4/2", "Downsampling 8/8/4", "Downsampling 16/16/8" };
	public static int defaultDownsamplingChoice = 2;

	public static String[] threshold = new String[]{ "Low Threshold (many points)", "Average Threshold", "High Threshold (few points)" };
	public static int defaultThresholdChoice = 1;

	public static String[] distance = new String[]{ "Fine Adjustment (<1px)", "Normal Adjustment (<5px)", "Gross Adjustment (<20px, careful)" };
	public static int defaultDistanceChoice = 1;

	public static int defaultLabelDialog = 0;
	public static int defaultChannelChoice = 0;
	public static double defaultICPError = 5;
	public static int defaultModel = 2;
	public static boolean defaultRegularize = true;

	public static class ICPRefinementParameters
	{
		public boolean groupTiles, groupIllums, groupChannels;

		// some channels that should not be grouped
		public ArrayList< Integer > doNotGroupChannels = new ArrayList<>();
		// some illuminations that should not be grouped
		//public ArrayList< Integer > doNotGroupIllums = new ArrayList<>();
		// some tiles that should not be grouped
		//public ArrayList< Integer > doNotGroupTiles = new ArrayList<>();

		public String label, transformationDescription;
		public double maxError;
		public AbstractModel< ? > transformationModel;

		final List<ViewId > viewIds;

		public ICPRefinementParameters( final List<ViewId > viewIds )
		{
			this.viewIds = viewIds;
		}

		public String toString()
		{
			String o = "ICPRefinementParameters:\n"
					+ "Interest point label: " + label + "\n"
					+ "maxError: " + maxError + "\n"
					+ "transformationModel: " + transformationModel.getClass().getSimpleName() + "\n"
					+ "groupTiles: " + groupTiles + "\n"
					+ "groupIllums: " + groupIllums + "\n"
					+ "groupChannels: " + groupChannels + "\n"
					+ "doNotGroupChannels: [";

					for ( final int ch : doNotGroupChannels )
						o += ch + ", ";

					o += "]";

					return o;
		}
	}

	public static ICPRefinementParameters initICPRefinement( final SpimData2 data, final List<? extends BasicViewDescription< ? > > selectedViews )
	{
		if ( StitchingUIHelper.allViews2D( selectedViews ) )
		{
			IOFunctions.println( "ICP refinement is currenty not supported for 2D: " + RefineWithICPPopup.class.getSimpleName() );
			return null;
		}

		// filter not present ViewIds
		final ArrayList< ViewId > viewIds = new ArrayList<>();
		viewIds.addAll( selectedViews );

		final List< ViewId > removed = SpimData2.filterMissingViews( data, viewIds );
		if ( removed.size() > 0 ) IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		if ( viewIds.size() <= 1 )
		{
			IOFunctions.println( "Only " + viewIds.size() + " views selected, need at least two for this to make sense." );
			return null;
		}

		return new ICPRefinementParameters( viewIds );
	}

	public static boolean getGUIParametersAdvanced( final SpimData2 data, final ICPRefinementParameters params )
	{
		//
		// get advanced parameters
		//
		final GenericDialog gd = new GenericDialog( "Expert Refine by ICP" );

		final ArrayList< String > labels = getAllLabels( params.viewIds, data );

		if ( labels.size() == 0 )
		{
			IOFunctions.println( "No interest point defined, please detect interest point and re-run" );
			new Interest_Point_Detection().detectInterestPoints( data, params.viewIds );
			return false;
		}

		final String[] labelChoice = new String[ labels.size() ];

		for ( int i = 0; i < labels.size(); ++i )
			labelChoice[ i ] = labels.get( i );

		if ( defaultLabelDialog >= labelChoice.length )
			defaultLabelDialog = 0;

		gd.addChoice( "Interest_Points", labelChoice, labelChoice[ defaultLabelDialog ] );
		gd.addNumericField( "ICP_maximum_error", defaultICPError, 2 );
		gd.addChoice( "Transformation model", TransformationModelGUI.modelChoice, TransformationModelGUI.modelChoice[ defaultModel ] );
		gd.addCheckbox( "Regularize_model", defaultRegularize );

		final ArrayList< Channel > channels = SpimData2.getAllChannelsSorted( data, params.viewIds );
		final String[] channelChoice = new String[ 2 + channels.size() ];
		channelChoice[ 0 ] = "Do not group";
		channelChoice[ 1 ] = "Group all";
		for ( int i = 0; i < channels.size(); ++i )
			channelChoice[ i + 2 ] = "Only channel " + channels.get( i ).getName();
		if ( defaultChannelChoice >= channelChoice.length )
			defaultChannelChoice = 0;

		gd.addChoice( "Group_channels", channelChoice, channelChoice[ defaultChannelChoice ] );
		gd.addCheckbox( "Group_tiles", false );
		gd.addCheckbox( "Group_illuminations", false );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return false;

		params.label = labels.get( defaultLabelDialog = gd.getNextChoiceIndex() );
		params.maxError = defaultICPError = gd.getNextNumber();
		TransformationModelGUI model = new TransformationModelGUI( defaultModel = gd.getNextChoiceIndex() );

		if ( defaultRegularize = gd.getNextBoolean() )
			if ( !model.queryRegularizedModel() )
				return false;

		params.transformationModel = model.getModel();

		final int channelGroup = gd.getNextChoiceIndex();
		if ( channelGroup > 0 )
		{
			params.groupChannels = true;
			if ( channelGroup >= 2 )
			{
				for ( int i = 0; i < channels.size(); ++i )
				{
					if ( channelGroup - 2 != i )
						params.doNotGroupChannels.add( channels.get( i ).getId() );
					else
						IOFunctions.println( "Only grouping tiles & illuminations for channel: " + channels.get( i ).getName() );
				}
			}
		}
		else
		{
			params.groupChannels = false;
		}
		params.groupTiles = gd.getNextBoolean();
		params.groupIllums = gd.getNextBoolean();

		params.transformationDescription = "Expert ICP Refinement";

		return true;
	}

	public static boolean getGUIParametersSimple(
			final ICPType icpType,
			final SpimData2 data,
			final ICPRefinementParameters params,
			final int downsamplingChoice,
			final int thresholdChoice,
			final int distanceChoice )
	{
		if ( icpType == ICPType.TileRefine )
		{
			// if we refine tiles only, we just group everything else together
			params.groupTiles = false;
			params.groupChannels = true;
			params.groupIllums = true;
			params.transformationDescription = "Tile ICP Refinement";
		}
		else if ( icpType == ICPType.ChromaticAbberation )// chromatic aberration
		{
			// if we do chromatic abberation correction, we should group the tiles,
			// but only those of one of the channels so the other channels' tiles can
			// float all freely around
			params.groupTiles = true;
			params.groupChannels = false;
			params.groupIllums = true;
			params.transformationDescription = "Chromatic Aberration Correction (ICP)";

			final ArrayList< Channel > channels = SpimData2.getAllChannelsSorted( data, params.viewIds );
			if ( channels.size() <= 1 )
			{
				IOFunctions.println( "Only one channel selected, cannot do a chromatic aberration correction." );
				return false;
			}

			IOFunctions.println( "Only grouping tiles & illuminations for channel: " + channels.get( 0 ).getName() );

			for ( int i = 1; i < channels.size(); ++i )
				params.doNotGroupChannels.add( channels.get( i ).getId() );
		}
		else //all
		{
			// if we refine tiles only, we just group everything else together
			params.groupTiles = false;
			params.groupChannels = false;
			params.groupIllums = false;
			params.transformationDescription = "ICP Refinement (over all)";
		}

		params.label = "forICP_" + downsamplingChoice + "_" + thresholdChoice;

		// DoG
		if ( !presentForAll( params.label, params.viewIds, data ) )
		{
			// each channel get the same min/max intensity for the interestpoints
			final HashSet< Class<? extends Entity> > factors = new HashSet<>();
			factors.add( Channel.class );

			for ( final Group< ViewDescription > group : Group.splitBy( SpimData2.getAllViewDescriptionsSorted( data,params.viewIds ), factors ) )
			{
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Processing channel: " + group.getViews().iterator().next().getViewSetup().getChannel().getName() );

				final DoGParameters dog = new DoGParameters();

				dog.imgloader = data.getSequenceDescription().getImgLoader();
				dog.toProcess = new ArrayList< ViewDescription >();
				dog.toProcess.addAll( group.getViews() );

				if ( thresholdChoice == 0 )
					dog.threshold = 0.001;
				else if ( thresholdChoice == 1 )
					dog.threshold = 0.0075;
				else //if ( defaultThreshold == 2 )
					dog.threshold = 0.015;

				switch ( downsamplingChoice )
				{
					case 0:
						dog.downsampleXY = 2;
						dog.downsampleZ = 1;
						break;
					case 1:
						dog.downsampleXY = 4;
						dog.downsampleZ = 2;
						break;
					case 2:
						dog.downsampleXY = 8;
						dog.downsampleZ = 4;
						break;
					case 3:
						dog.downsampleXY = 16;
						dog.downsampleZ = 8;
						break;
					default:
						dog.downsampleXY = 4;
						dog.downsampleZ = 2;
						break;
				}

				dog.sigma = 1.6;

				dog.limitDetections = true;
				dog.maxDetections = 10000;
				dog.maxDetectionsTypeIndex = 0; // brightest

				IOFunctions.println( "DoG Threshold = " + dog.threshold );
				IOFunctions.println( "DoG Sigma = " + dog.sigma );

				dog.showProgress( 0, 1 );

				if ( group.getViews().size() > 1 )
				{
					final double[] minmax = minmax( data, dog.toProcess );
					dog.minIntensity = minmax[ 0 ];
					dog.maxIntensity = minmax[ 1 ];
				}

				final HashMap< ViewId, List< InterestPoint > > points = DoG.findInterestPoints( dog );

				InterestPointTools.addInterestPoints( data, params.label, points, "DoG, sigma=1.4, downsampleXY=" + dog.downsampleXY + ", downsampleZ=" + dog.downsampleZ );
			}
		}
		else
		{
			IOFunctions.println( "Interestpoint '" + params.label + "' already defined for all views, using those." );
		}

		params.transformationModel = new InterpolatedAffineModel3D< AffineModel3D, RigidModel3D >(
				new AffineModel3D(),
				new RigidModel3D(),
				0.1f );

		if ( distanceChoice == 0 )
			params.maxError = defaultICPError = 1.0;
		else if ( distanceChoice == 1 )
			params.maxError = defaultICPError = 5.0;
		else
			params.maxError = defaultICPError = 20;

		return true;
	}

	public static void refine(
			final SpimData2 data,
			final ICPRefinementParameters params,
			final GlobalOptimizationParameters globalOptParameters,
			final DemoLinkOverlay overlay )
	{
		IOFunctions.println( params );

		//
		// run the alignment
		//
		final IterativeClosestPointParameters icpp =
				new IterativeClosestPointParameters(
						params.transformationModel,
						params.maxError * 2,
						100,
						true,
						RANSACParameters.min_inlier_ratio,
						params.maxError,
						200,
						IterativeClosestPointParameters.defaultMinNumPoints );

		final Map< ViewId, HashMap< String, Double > > labelMap = new HashMap<>();

		for ( final ViewId viewId : params.viewIds )
		{
			final HashMap< String, Double > map = new HashMap<>();
			map.put( params.label, 1.0 );
			labelMap.put( viewId, map );
		}

		// load & transform all interest points
		final Map< ViewId, HashMap< String, Collection< InterestPoint > > > interestpoints =
				TransformationTools.getAllTransformedInterestPoints(
					params.viewIds,
					data.getViewRegistrations().getViewRegistrations(),
					data.getViewInterestPoints().getViewInterestPoints(),
					labelMap );

		// identify groups/subsets
		Set< Group< ViewId > > groups = AdvancedRegistrationParameters.getGroups( data, params.viewIds, params.groupTiles, params.groupIllums, params.groupChannels, false );

		if ( params.doNotGroupChannels.size() > 0 )
		{
			IOFunctions.println( "Groups before: ");

			for ( final Group< ViewId > group : groups )
				IOFunctions.println( group );

			for ( final int channelId : params.doNotGroupChannels )
				groups = splitGroupsForChannelOverTile( data, groups, channelId );

			IOFunctions.println( "Groups after splitting: ");

			for ( final Group< ViewId > group : groups )
				IOFunctions.println( group );
		}

		final PairwiseSetup< ViewId > setup = new AllToAll<>( params.viewIds, groups );
		IOFunctions.println( "Defined pairs, removed " + setup.definePairs().size() + " redundant view pairs." );
		IOFunctions.println( "Removed " + setup.removeNonOverlappingPairs( new SimpleBoundingBoxOverlap<>( data ) ).size() + " pairs because they do not overlap." );
		setup.reorderPairs();
		setup.detectSubsets();
		setup.sortSubsets();
		final ArrayList< Subset< ViewId > > subsets = setup.getSubsets();
		IOFunctions.println( "Identified " + subsets.size() + " subsets " );

		if ( overlay != null )
		{
			overlay.getFilteredResults().clear();
			overlay.getInconsistentResults().clear();
		}

		for ( final Subset< ViewId > subset : subsets )
		{
			// fix view(s)
			final List< ViewId > fixedViews = setup.getDefaultFixedViews();
			final ViewId fixedView = subset.getViews().iterator().next();
			fixedViews.add( fixedView );
			IOFunctions.println( "Removed " + subset.fixViews( fixedViews ).size() + " views due to fixing view tpId=" + fixedView.getTimePointId() + " setupId=" + fixedView.getViewSetupId() );

			HashMap< ViewId, mpicbg.models.Tile > models;

			if ( Interest_Point_Registration.hasGroups( subsets ) )
				models = groupedSubset( data, subset, interestpoints, labelMap, icpp, fixedViews, data.getSequenceDescription().getViewSetups(), data.getViewRegistrations().getViewRegistrations(), globalOptParameters, overlay, false );
			else
				models = pairSubset( data, subset, interestpoints, labelMap, icpp, fixedViews, data.getSequenceDescription().getViewSetups(), data.getViewRegistrations().getViewRegistrations(), globalOptParameters, overlay, false );

			if ( models == null )
				continue;

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Final transformation models (without mapback model):" );

			// pre-concatenate models to spimdata2 viewregistrations (from SpimData(2))
			for ( final ViewId viewId : subset.getViews() )
			{
				final mpicbg.models.Tile tile = models.get( viewId );
				final ViewRegistration vr = data.getViewRegistrations().getViewRegistrations().get( viewId );

				TransformationTools.storeTransformation( vr, viewId, tile, null, params.transformationDescription );

				// TODO: We assume it is Affine3D here
				String output = Group.pvid( viewId ) + ": " + TransformationTools.printAffine3D( (Affine3D<?>)tile.getModel() );

				if ( tile.getModel() instanceof RigidModel3D )
					IOFunctions.println( output + ", " + TransformationTools.getRotationAxis( (RigidModel3D)tile.getModel() ) );
				else
					IOFunctions.println( output + ", " + TransformationTools.getScaling( (Affine3D<?>)tile.getModel() ) );
			}
		}
	}

	public static final HashMap< ViewId, mpicbg.models.Tile > pairSubset(
			final SpimData2 spimData,
			final Subset< ViewId > subset,
			final Map< ViewId, HashMap< String, Collection< InterestPoint > > > interestpoints,
			final Map< ViewId, HashMap< String, Double > > labelMap,
			final IterativeClosestPointParameters icpp,
			final List< ViewId > fixedViews,
			final Map< Integer, ? extends BasicViewSetup > viewSetups, // for two-round
			final Map< ViewId, ViewRegistration > registrations, // for two-round
			final GlobalOptimizationParameters globalOptParameters,
			final DemoLinkOverlay overlay,
			final boolean matchAcrossLabels )
	{
		final List< Pair< ViewId, ViewId > > pairs = subset.getPairs();

		if ( pairs.size() <= 0 )
		{
			IOFunctions.println( "No image pair for comparison left, we need at least one pair for this to make sense." );
			return null;
		}

		for ( final Pair< ViewId, ViewId > pair : pairs )
			System.out.println( Group.pvid( pair.getA() ) + " <=> " + Group.pvid( pair.getB() ) );

		// compute all pairwise matchings
		final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > > resultsPairs =
				MatcherPairwiseTools.computePairs( pairs, interestpoints, new IterativeClosestPointPairwise< InterestPoint >( icpp ), matchAcrossLabels );

		if ( overlay != null )
		{
			final HashSet< Pair< Group< ViewId >, Group< ViewId > > > results = new HashSet<>();

			for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > result : resultsPairs  )
			{
				if ( result.getB().getInliers().size() > 0 )
				{
					results.add( new ValuePair< Group<ViewId>, Group<ViewId> >( new Group< ViewId >( result.getA().getA() ), new Group< ViewId >( result.getA().getB() ) ) );
				}
			}

			overlay.setPairwiseLinkInterface( new PairwiseLinkImpl( results ) );
		}

		// clear correspondences
		MatcherPairwiseTools.clearCorrespondences( subset.getViews(), spimData.getViewInterestPoints().getViewInterestPoints(), labelMap );

		// add the corresponding detections and output result
		for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > p : resultsPairs )
		{
			final ViewId vA = p.getA().getA();
			final ViewId vB = p.getA().getB();

			/*
			final InterestPoints listA = spimData.getViewInterestPoints().getViewInterestPoints().get( vA ).getInterestPointList( labelMap.get( vA ) );
			final InterestPoints listB = spimData.getViewInterestPoints().getViewInterestPoints().get( vB ).getInterestPointList( labelMap.get( vB ) );

			MatcherPairwiseTools.addCorrespondences( p.getB().getInliers(), vA, vB, labelMap.get( vA ), labelMap.get( vB ), listA, listB );
			*/

			final String labelA = p.getB().getLabelA();
			final String labelB = p.getB().getLabelB();

			final InterestPoints listA = spimData.getViewInterestPoints().getViewInterestPoints().get( vA ).getInterestPointList( labelA );
			final InterestPoints listB = spimData.getViewInterestPoints().getViewInterestPoints().get( vB ).getInterestPointList( labelB );

			MatcherPairwiseTools.addCorrespondences( p.getB().getInliers(), vA, vB, labelA, labelB, listA, listB );

			IOFunctions.println( p.getB().getFullDesc() );
		}

		// multiple solvers for ICP
		final PointMatchCreator pmc = new InterestPointMatchCreator( resultsPairs, labelMap );
		final HashMap< ViewId, mpicbg.models.Tile > models;

		if ( globalOptParameters.method == GlobalOptType.ONE_ROUND_SIMPLE )
		{
			models = (HashMap< ViewId, mpicbg.models.Tile >)(Object)GlobalOpt.computeTiles(
							(Model)(Object)icpp.getModel().copy(),
							globalOptParameters.preAlign,
							pmc,
							new ConvergenceStrategy( icpp.getMaxDistance() ),
							fixedViews,
							subset.getGroups() );
		}
		else if ( globalOptParameters.method == GlobalOptType.ONE_ROUND_ITERATIVE )
		{
			models = (HashMap< ViewId, mpicbg.models.Tile >)(Object)GlobalOptIterative.computeTiles(
							(Model)(Object)icpp.getModel().copy(),
							globalOptParameters.preAlign,
							pmc,
							new SimpleIterativeConvergenceStrategy( icpp.getMaxDistance(), globalOptParameters.relativeThreshold, globalOptParameters.absoluteThreshold ),
							new MaxErrorLinkRemoval(),
							null,
							fixedViews,
							subset.getGroups() );
		}
		else //if ( globalOptParameters.method == GlobalOptType.TWO_ROUND_SIMPLE || globalOptParameters.method == GlobalOptType.TWO_ROUND_ITERATIVE )
		{
			models = (HashMap< ViewId, mpicbg.models.Tile >)(Object)GlobalOptTwoRound.computeTiles(
					(Model & Affine3D)(Object)icpp.getModel().copy(),
					globalOptParameters.preAlign,
					pmc,
					new SimpleIterativeConvergenceStrategy( icpp.getMaxDistance(), globalOptParameters.relativeThreshold, globalOptParameters.absoluteThreshold ), // if it's simple, both will be Double.MAX
					new MaxErrorLinkRemoval(),
					null,
					new MetaDataWeakLinkFactory(
							registrations,
							new SimpleBoundingBoxOverlap<>( viewSetups, registrations ) ),
					new ConvergenceStrategy( Double.MAX_VALUE ),
					fixedViews,
					subset.getGroups() );
		}

		return models;

		// run global optimization
		//return (HashMap< ViewId, mpicbg.models.Tile >)GlobalOpt.computeTiles( (Model)icpp.getModel().copy(), pmc, cs, fixedViews, subset.getGroups() );
	}

	public static HashMap< ViewId, mpicbg.models.Tile > groupedSubset(
			final SpimData2 spimData,
			final Subset< ViewId > subset,
			final Map< ViewId, HashMap< String, Collection< InterestPoint > > > interestpoints,
			final Map< ViewId, HashMap< String, Double > > labelMap,
			final IterativeClosestPointParameters icpp,
			final List< ViewId > fixedViews,
			final Map< Integer, ? extends BasicViewSetup > viewSetups, // for two-round
			final Map< ViewId, ViewRegistration > registrations, // for two-round
			final GlobalOptimizationParameters globalOptParameters,
			final DemoLinkOverlay overlay,
			final boolean matchAcrossLabels )
	{
		final List< Pair< Group< ViewId >, Group< ViewId > > > groupedPairs = subset.getGroupedPairs();
		final Map< Group< ViewId >, HashMap< String, Collection< GroupedInterestPoint< ViewId > > > > groupedInterestpoints = new HashMap<>();
		final InterestPointGrouping< ViewId > ipGrouping = new InterestPointGroupingMinDistance<>( interestpoints );

		if ( groupedPairs.size() <= 0 )
		{
			IOFunctions.println( "No pair of grouped images for comparison left, we need at least one pair for this to make sense." );
			return null;
		}

		// which groups exist
		final Set< Group< ViewId > > groups = new HashSet<>();

		for ( final Pair< Group< ViewId >, Group< ViewId > > pair : groupedPairs )
		{
			groups.add( pair.getA() );
			groups.add( pair.getB() );

			System.out.print( "[" + pair.getA() + "] <=> [" + pair.getB() + "]" );

			if ( !groupedInterestpoints.containsKey( pair.getA() ) )
			{
				System.out.print( ", grouping interestpoints for " + pair.getA() );

				groupedInterestpoints.put( pair.getA(), ipGrouping.group( pair.getA() ) );
			}

			if ( !groupedInterestpoints.containsKey( pair.getB() ) )
			{
				System.out.print( ", grouping interestpoints for " + pair.getB() );

				groupedInterestpoints.put( pair.getB(), ipGrouping.group( pair.getB() ) );
			}

			System.out.println();
		}

		final List< Pair< Pair< Group< ViewId >, Group< ViewId > >, PairwiseResult< GroupedInterestPoint< ViewId > > > > resultsGroups =
				MatcherPairwiseTools.computePairs( groupedPairs, groupedInterestpoints, new IterativeClosestPointPairwise< GroupedInterestPoint< ViewId > >( icpp ), false );

		if ( overlay != null )
		{
			final HashSet< Pair< Group< ViewId >, Group< ViewId > > > results = new HashSet<>();

			for ( final Pair< Pair< Group< ViewId >, Group< ViewId > >, PairwiseResult< GroupedInterestPoint< ViewId > > > result : resultsGroups  )
				if ( result.getB().getInliers().size() > 0 )
					results.add( result.getA() );

			overlay.setPairwiseLinkInterface( new PairwiseLinkImpl( results ) );
		}

		// clear correspondences and get a map linking ViewIds to the correspondence lists
		final Map< ViewId, HashMap< String, List< CorrespondingInterestPoints > > > cMap =
				MatcherPairwiseTools.clearCorrespondences( subset.getViews(), spimData.getViewInterestPoints().getViewInterestPoints(), labelMap );

		// add the corresponding detections and output result
		final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< GroupedInterestPoint< ViewId > > > > resultG =
				MatcherPairwiseTools.addCorrespondencesFromGroups( resultsGroups, spimData.getViewInterestPoints().getViewInterestPoints(), cMap );

		// run global optimization
		final PointMatchCreator pmc = new InterestPointMatchCreator( resultG, labelMap );

		// multiple solvers for ICP
		final HashMap< ViewId, mpicbg.models.Tile > models;

		if ( globalOptParameters.method == GlobalOptType.ONE_ROUND_SIMPLE )
		{
			models = (HashMap< ViewId, mpicbg.models.Tile >)(Object)GlobalOpt.computeTiles(
							(Model)(Object)icpp.getModel().copy(),
							globalOptParameters.preAlign,
							pmc,
							new ConvergenceStrategy( icpp.getMaxDistance() ),
							fixedViews,
							groups );
		}
		else if ( globalOptParameters.method == GlobalOptType.ONE_ROUND_ITERATIVE )
		{
			models = (HashMap< ViewId, mpicbg.models.Tile >)(Object)GlobalOptIterative.computeTiles(
							(Model)(Object)icpp.getModel().copy(),
							globalOptParameters.preAlign,
							pmc,
							new SimpleIterativeConvergenceStrategy( icpp.getMaxDistance(), globalOptParameters.relativeThreshold, globalOptParameters.absoluteThreshold ),
							new MaxErrorLinkRemoval(),
							null,
							fixedViews,
							groups );
		}
		else //if ( globalOptParameters.method == GlobalOptType.TWO_ROUND_SIMPLE || globalOptParameters.method == GlobalOptType.TWO_ROUND_ITERATIVE )
		{
			models = (HashMap< ViewId, mpicbg.models.Tile >)(Object)GlobalOptTwoRound.computeTiles(
					(Model & Affine3D)(Object)icpp.getModel().copy(),
					globalOptParameters.preAlign,
					pmc,
					new SimpleIterativeConvergenceStrategy( icpp.getMaxDistance(), globalOptParameters.relativeThreshold, globalOptParameters.absoluteThreshold ), // if it's simple, both will be Double.MAX
					new MaxErrorLinkRemoval(),
					null,
					new MetaDataWeakLinkFactory(
							registrations,
							new SimpleBoundingBoxOverlap<>( viewSetups, registrations ) ),
					new ConvergenceStrategy( Double.MAX_VALUE ),
					fixedViews,
					groups );
		}

		return models;

		//return (HashMap< ViewId, mpicbg.models.Tile >)GlobalOpt.compute( (Model)icpp.getModel().copy(), pmc, cs, fixedViews, groups );
	}

	/**
	 * TODO: this is just a hack, we need to change the original splitorcombine method in Group to support not only classes, but classed + ids
	 * 
	 * @param spimData
	 * @param groups
	 * @param splitChannel
	 */
	private static HashSet< Group< ViewId > > splitGroupsForChannelOverTile( final SpimData2 spimData, final Set< Group< ViewId > > groups, final int splitChannel )
	{
		final HashSet< Group< ViewId > > newGroups = new HashSet<>();

		final Group< ViewId > remainingGroup = new Group<>();
		final HashMap< Tile, Group< ViewId > > result = new HashMap<>();

		for ( final Group< ViewId > group : groups )
		{
			for ( final ViewId viewId : group.getViews() )
			{
				final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( viewId );

				if ( splitChannel == vd.getViewSetup().getChannel().getId() )
				{
					// group the still grouped ones (e.g. illuminations)
					if ( result.containsKey( vd.getViewSetup().getTile() ) )
						result.get( vd.getViewSetup().getTile() ).getViews().add( viewId );
					else
						result.put( vd.getViewSetup().getTile(), new Group<>( viewId ) );
				}
				else
				{
					remainingGroup.getViews().add( viewId );
				}
			}
		}

		if ( remainingGroup.size() > 0 )
			newGroups.add( remainingGroup );

		newGroups.addAll( result.values() );

		return newGroups;
	}

	public static double[] minmax( final SpimData2 spimData, final Collection< ? extends ViewId > viewIdsToProcess )
	{
		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Determining it approximate Min & Max for all views at lowest resolution levels ... " );

		IJ.showProgress( 0 );

		final ImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();

		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;

		int count = 0;
		for ( final ViewId view : viewIdsToProcess )
		{
			final double[] minmax = FusionTools.minMaxApprox1( DownsampleTools.openAtLowestLevel( imgLoader, view ) );
			min = Math.min( min, minmax[ 0 ] );
			max = Math.max( max, minmax[ 1 ] );

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): View " + Group.pvid( view ) + ", Min=" + minmax[ 0 ] + " max=" + minmax[ 1 ] );

			IJ.showProgress( (double)++count / viewIdsToProcess.size() );
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Total Min=" + min + " max=" + max );

		return new double[]{ min, max };
	}

	public static boolean presentForAll( final String label, final Collection< ? extends ViewId > viewIds, final SpimData2 data )
	{
		for ( final ViewId viewId : viewIds )
			if ( data.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( label ) == null )
				return false;

		return true;
	}

	public static ArrayList< String > getAllLabels( final Collection< ? extends ViewId > viewIds, final SpimData2 data )
	{
		final ViewId view1 = viewIds.iterator().next();

		final Set< String > labels = data.getViewInterestPoints().getViewInterestPointLists( view1 ).getHashMap().keySet();
		final ArrayList< String > presentLabels = new ArrayList<>();

		for ( final String label : labels )
			if ( presentForAll( label, viewIds, data ) )
				presentLabels.add( label );

		Collections.sort( presentLabels );

		return presentLabels;
	}
}
