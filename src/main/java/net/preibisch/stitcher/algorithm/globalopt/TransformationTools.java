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
package net.preibisch.stitcher.algorithm.globalopt;


import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import bdv.export.ProgressWriter;
import ij.IJ;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Translation;
import net.imglib2.realtransform.TranslationGet;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseStitchingResult;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximalGroupOverlap;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOpt;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.ConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.strong.ImageCorrelationPointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.algorithm.GroupedViewAggregator;
import net.preibisch.stitcher.algorithm.GroupedViewAggregator.ActionType;
import net.preibisch.stitcher.algorithm.PairwiseStitching;
import net.preibisch.stitcher.algorithm.PairwiseStitchingParameters;
import net.preibisch.stitcher.algorithm.TransformTools;
import net.preibisch.stitcher.algorithm.lucaskanade.LucasKanadeParameters;
import net.preibisch.stitcher.gui.popup.DisplayOverlapTestPopup;
import net.preibisch.stitcher.input.GenerateSpimData;

public class TransformationTools
{
	public static int maxNumOutputLines = 200;

	public static < A > Pair< A, A > reversePair( final Pair< A, A > pair )
	{
		return new ValuePair< A, A >( pair.getB(), pair.getA() );
	}

	public static < T extends RealType< T > > Pair<Pair< AffineGet, Double >, RealInterval> computeStitchingNonEqualTransformations(
			final Group<? extends ViewId> viewIdsA,
			final Group<? extends ViewId> viewIdsB,
			final ViewRegistrations vrs,
			final PairwiseStitchingParameters params,
			final AbstractSequenceDescription< ?,? extends BasicViewDescription<?>, ? > sd,
			final GroupedViewAggregator gva,
			final long[] downsampleFactors,
			final ExecutorService service )
	{

		final double[] downsampleDbl = new double[downsampleFactors.length];
		for (int d = 0; d < downsampleFactors.length; d++)
			downsampleDbl[d] = downsampleFactors[d];

		// get Overlap Bounding Box
		final List<List<ViewId>> views = new ArrayList<>();
		views.add( new ArrayList<>(viewIdsA.getViews()) );
		views.add( new ArrayList<>(viewIdsB.getViews()) );
		BoundingBoxMaximalGroupOverlap< ViewId > bbDet = new BoundingBoxMaximalGroupOverlap<ViewId>( views, sd, vrs );
		BoundingBox bbOverlap = bbDet.estimate( "Max Overlap" );

		// we could not find overlap -> ignore this pair
		if (bbOverlap == null)
			return null;

		List<RandomAccessibleInterval< FloatType >> raiOverlaps = new ArrayList<>();		
		for (List< ViewId > tileViews : views)
		{
			// wrap every view id (corresponding e.g. to different channels, illums,.. ) in list
			List<List< ViewId >> wrapped = tileViews.stream().map( v -> {
				ArrayList< ViewId > wrp = new ArrayList<ViewId>();
				wrp.add( v );
				return wrp;} ).collect( Collectors.toList() );

			// open all of them "virtually fused"
			List< RandomAccessibleInterval< FloatType > > openFused = 
					DisplayOverlapTestPopup.openVirtuallyFused( sd, vrs, wrapped, bbOverlap, downsampleDbl );

			// aggregate the group into one image
			RandomAccessibleInterval< FloatType > raiI = gva.aggregate( 
					openFused, 
					tileViews,
					sd );

			raiOverlaps.add(raiI);
		}

		// the overlap in both images
		final RandomAccessibleInterval< FloatType > img1 = raiOverlaps.get(0);
		final RandomAccessibleInterval< FloatType > img2 = raiOverlaps.get(1);
		
		// compute phase correlation shift (passing (0,0,..) translations prevents any overlap correction inside)
		final Pair< Translation, Double > result = PairwiseStitching.getShift(
				img1,
				img2,
				new Translation( img1.numDimensions() ),
				new Translation( img1.numDimensions() ),
				params,
				service );

		if (result == null)
			return null;

		for (int i = 0; i< result.getA().numDimensions(); ++i)			
			result.getA().set( result.getA().get(i, result.getA().numDimensions()) * downsampleFactors[i], i ); 

		// TODO (?): Different translational part of downsample Transformations should be considered via TransformTools.getInitialTransforms
		// we probalbly do not have to correct for them ?
		final AffineTransform3D vr = vrs.getViewRegistration(viewIdsB.iterator().next()).getModel();		
		final AffineTransform resCorrected = new AffineTransform( result.getA().numDimensions() );
		resCorrected.set( result.getA() );

		if ( PairwiseStitching.debug )
		{
			System.out.println("shift: " + Util.printCoordinates(result.getA().getTranslationCopy()));
			System.out.print("cross-corr: " + result.getB());
		}

		return new ValuePair<>( new ValuePair<>( resCorrected, result.getB() ), bbOverlap );
	}
	
	public static < T extends RealType< T > > Pair<Pair< AffineGet, Double >, RealInterval> computeStitchingNonEqualTransformationsLucasKanade(
			final Group<? extends ViewId> viewIdsA,
			final Group<? extends ViewId> viewIdsB,
			final ViewRegistrations vrs,
			final LucasKanadeParameters params,
			final AbstractSequenceDescription< ?,? extends BasicViewDescription<?>, ? > sd,
			final GroupedViewAggregator gva,
			final long[] downsampleFactors,
			final ExecutorService service )
	{
		final double[] downsampleDbl = new double[downsampleFactors.length];
		for (int d = 0; d < downsampleFactors.length; d++)
			downsampleDbl[d] = downsampleFactors[d];

		// get Overlap Bounding Box
		final List<List<ViewId>> views = new ArrayList<>();
		views.add( new ArrayList<>(viewIdsA.getViews()) );
		views.add( new ArrayList<>(viewIdsB.getViews()) );
		BoundingBoxMaximalGroupOverlap< ViewId > bbDet = new BoundingBoxMaximalGroupOverlap<ViewId>( views, sd, vrs );
		BoundingBox bbOverlap = bbDet.estimate( "Max Overlap" );

		// we could not find overlap -> ignore this pair
		if (bbOverlap == null)
			return null;

		List<RandomAccessibleInterval< FloatType >> raiOverlaps = new ArrayList<>();		
		for (List< ViewId > tileViews : views)
		{
			// wrap every view id (corresponding e.g. to different channels, illums,.. ) in list
			List<List< ViewId >> wrapped = tileViews.stream().map( v -> {
				ArrayList< ViewId > wrp = new ArrayList<ViewId>();
				wrp.add( v );
				return wrp;} ).collect( Collectors.toList() );

			// open all of them "virtually fused"
			List< RandomAccessibleInterval< FloatType > > openFused = 
					DisplayOverlapTestPopup.openVirtuallyFused( sd, vrs, wrapped, bbOverlap, downsampleDbl );

			// aggregate the group into one image
			RandomAccessibleInterval< FloatType > raiI = gva.aggregate( 
					openFused, 
					tileViews,
					sd );

			raiOverlaps.add(raiI);
		}

		// the overlap in both images
		final RandomAccessibleInterval< FloatType > img1 = raiOverlaps.get(0);
		final RandomAccessibleInterval< FloatType > img2 = raiOverlaps.get(1);
		
		// compute phase correlation shift (passing (0,0,..) translations prevents any overlap correction inside)
		final Pair< AffineTransform, Double > result = PairwiseStitching.getShiftLucasKanade(
				img1,
				img2,
				new Translation( img1.numDimensions() ),
				new Translation( img1.numDimensions() ),
				params,
				service );

		if (result == null)
			return null;

		// scale just the translational part
		for (int i = 0; i< result.getA().numDimensions(); ++i)			
			result.getA().set( result.getA().get(i, result.getA().numDimensions()) * downsampleFactors[i], i ); 

		// TODO (?): Different translational part of downsample Transformations should be considered via TransformTools.getInitialTransforms
		// we probalbly do not have to correct for them ?

		final AffineTransform3D vr = vrs.getViewRegistration(viewIdsB.iterator().next()).getModel();		
		final AffineTransform resCorrected = new AffineTransform( result.getA().numDimensions() );
		resCorrected.set( result.getA() );

		if ( PairwiseStitching.debug )
			IOFunctions.println("resulting transformation: " + Util.printCoordinates(result.getA().getRowPackedCopy()));

		return new ValuePair<>( new ValuePair<>( resCorrected, result.getB() ), bbOverlap );
	}
	
	public static < T extends RealType< T > > Pair<Pair< AffineGet, Double >, RealInterval> computeStitching(
			final Group<? extends ViewId> viewIdsA,
			final Group<? extends ViewId> viewIdsB,
			final ViewRegistrations vrs,
			final PairwiseStitchingParameters params,
			final AbstractSequenceDescription< ?,? extends BasicViewDescription<?>, ? > sd,
			final GroupedViewAggregator gva,
			final long[] downsampleFactors,
			final ExecutorService service )
	{

		// the transformation that maps the downsampled image coordinates back to the original input(!) image space
		final AffineTransform3D dsCorrectionT1 = new AffineTransform3D();
		final AffineTransform3D dsCorrectionT2 = new AffineTransform3D();

		// get Overlap Bounding Box
		final List<List<ViewId>> views = new ArrayList<>();
		views.add( new ArrayList<>(viewIdsA.getViews()) );
		views.add( new ArrayList<>(viewIdsB.getViews()) );
		BoundingBoxMaximalGroupOverlap< ViewId > bbDet = new BoundingBoxMaximalGroupOverlap<ViewId>( views, sd, vrs );
		BoundingBox bbOverlap = bbDet.estimate( "Max Overlap" );

		// this should be caught outside of this method already, but check nonetheless
		if (bbOverlap == null)
			return null;

		// get one image per group
		final RandomAccessibleInterval<T> img1 = gva.aggregate( viewIdsA, sd, downsampleFactors, dsCorrectionT1, service );
		final RandomAccessibleInterval<T> img2 = gva.aggregate( viewIdsB, sd, downsampleFactors, dsCorrectionT2, service );

		if (img1 == null || img2 == null)
		{
			IOFunctions.println( "WARNING: Tried to open missing View when computing Stitching for " + viewIdsA + " and " + 
						viewIdsB + ". No link between those could be determined");
			return null;
		}

		// get translations
		// TODO: is the 2d check here meaningful?
		// everything will probably be 3d at this point, since ImgLoaders return 3d images
		boolean is2d = img1.numDimensions() == 2;
		Pair< AffineGet, TranslationGet > t1 = TransformTools.getInitialTransforms( vrs.getViewRegistration(viewIdsA.iterator().next()), is2d, dsCorrectionT1 );
		Pair< AffineGet, TranslationGet > t2 = TransformTools.getInitialTransforms( vrs.getViewRegistration(viewIdsB.iterator().next()), is2d, dsCorrectionT2 );

		final Pair< Translation, Double > result  = PairwiseStitching.getShift( img1, img2, t1.getB(), t2.getB(), params, service );

		if (result == null)
			return null;
		
		for (int i = 0; i< result.getA().numDimensions(); ++i)			
			result.getA().set( result.getA().get(i, result.getA().numDimensions()) * downsampleFactors[i], i ); 

		// TODO (?): Different translational part of downsample Transformations should be considered via TransformTools.getInitialTransforms
		// we probalbly do not have to correct for them ?

		// NB: as we will deal in global coordinates, not pixel coordinates in global optimization,
		// calculate global R' = VT^-1 * R * VT from pixel transformation R 
		ViewRegistration vrOld = vrs.getViewRegistration(viewIdsB.iterator().next());
		AffineTransform3D resTransform = new AffineTransform3D();
		resTransform.set( result.getA().getRowPackedCopy() );
		resTransform.concatenate( vrOld.getModel().inverse() );
		resTransform.preConcatenate( vrOld.getModel() );

		if ( PairwiseStitching.debug )
		{
			System.out.println("shift (pixel coordinates): " + Util.printCoordinates(result.getA().getTranslationCopy()));
			System.out.println("shift (global coordinates): " + Util.printCoordinates(resTransform.getRowPackedCopy()));
			System.out.print("cross-corr: " + result.getB());
		}

		return new ValuePair<>( new ValuePair<>( resTransform, result.getB() ), bbOverlap );
	}
	
	public static < T extends RealType< T > > Pair<Pair< AffineGet, Double >, RealInterval> computeStitchingLucasKanade(
			final Group<? extends ViewId> viewIdsA,
			final Group<? extends ViewId> viewIdsB,
			final ViewRegistrations vrs,
			final LucasKanadeParameters params,
			final AbstractSequenceDescription< ?,? extends BasicViewDescription<?>, ? > sd,
			final GroupedViewAggregator gva,
			final long[] downsampleFactors,
			final ExecutorService service )
	{
		
		// the transformation that maps the downsampled image coordinates back to the original input(!) image space
		final AffineTransform3D dsCorrectionT1 = new AffineTransform3D();
		final AffineTransform3D dsCorrectionT2 = new AffineTransform3D();

		// get Overlap Bounding Box
		final List<List<ViewId>> views = new ArrayList<>();
		views.add( new ArrayList<>(viewIdsA.getViews()) );
		views.add( new ArrayList<>(viewIdsB.getViews()) );
		BoundingBoxMaximalGroupOverlap< ViewId > bbDet = new BoundingBoxMaximalGroupOverlap<ViewId>( views, sd, vrs );
		BoundingBox bbOverlap = bbDet.estimate( "Max Overlap" );

		// this should be caught outside of this method already, but check nonetheless
		if (bbOverlap == null)
			return null;

		// get one image per group
		final RandomAccessibleInterval<T> img1 = gva.aggregate( viewIdsA, sd, downsampleFactors, dsCorrectionT1, service );	
		final RandomAccessibleInterval<T> img2 = gva.aggregate( viewIdsB, sd, downsampleFactors, dsCorrectionT2, service );

		if (img1 == null || img2 == null)
		{
			IOFunctions.println( "WARNING: Tried to open missing View when computing Stitching for " + viewIdsA + " and " + 
						viewIdsB + ". No link between those could be determined");
			return null;
		}

		// get translations
		// TODO: is the 2d check here meaningful?
		boolean is2d = img1.numDimensions() == 2;
		Pair< AffineGet, TranslationGet > t1 = TransformTools.getInitialTransforms( vrs.getViewRegistration(viewIdsA.iterator().next()), is2d, dsCorrectionT1 );
		Pair< AffineGet, TranslationGet > t2 = TransformTools.getInitialTransforms( vrs.getViewRegistration(viewIdsB.iterator().next()), is2d, dsCorrectionT2 );

		final Pair< AffineTransform, Double > result  = PairwiseStitching.getShiftLucasKanade(  img1, img2, t1.getB(), t2.getB(), params, service );

		if (result == null)
			return null;

		// TODO: is scaling just the translational part okay here?
		for (int i = 0; i< result.getA().numDimensions(); ++i)			
			result.getA().set( result.getA().get(i, result.getA().numDimensions()) * downsampleFactors[i], i, result.getA().numDimensions() ); 

		// TODO (?): Different translational part of downsample Transformations should be considered via TransformTools.getInitialTransforms
		// we probalbly do not have to correct for them ?

		// NB: as we will deal in global coordinates, not pixel coordinates in global optimization,
		// calculate global R' = VT^-1 * R * VT from pixel transformation R 
		ViewRegistration vrOld = vrs.getViewRegistration(viewIdsB.iterator().next());
		AffineTransform3D resTransform = new AffineTransform3D();
		resTransform.set( result.getA().getRowPackedCopy() );
		resTransform.concatenate( vrOld.getModel().inverse() );
		resTransform.preConcatenate( vrOld.getModel() );

		IOFunctions.println("resulting transformation (pixel coordinates): " + Util.printCoordinates(result.getA().getRowPackedCopy()));
		IOFunctions.println("resulting transformation (global coordinates): " + Util.printCoordinates(resTransform.getRowPackedCopy()));

		return new ValuePair<>( new ValuePair<>( resTransform, result.getB() ), bbOverlap );
	}

	/**
	 * 
	 * @param pairs list of potentially overlapping pairs of view groups, this will be modified!
	 * @param vrs the view registrations
	 * @param sd the sequence description
	 * @param <V> view id type
	 * @return list of the pairs that were removed
	 */
	public static <V extends ViewId> List< Pair< Group< V >, Group< V > > > filterNonOverlappingPairs(
			List< Pair<  Group< V >,  Group< V > > > pairs,
			final ViewRegistrations vrs,
			final AbstractSequenceDescription< ?, ? extends BasicViewDescription< ? >, ? > sd
			)
	{
		
		final List< Pair<  Group< V >,  Group< V > > > removedPairs = new ArrayList<>();
		
		for (int i = pairs.size() - 1; i >= 0; i--)
		{
			final List<Set<V>> pairAsGroups = new ArrayList<>();
			pairAsGroups.add( pairs.get( i ).getA().getViews() );
			pairAsGroups.add( pairs.get( i ).getB().getViews() );
			
			final BoundingBoxMaximalGroupOverlap< V > ibbd = new BoundingBoxMaximalGroupOverlap< V >(pairAsGroups, sd, vrs);
			BoundingBox bb = ibbd.estimate( "max overlap" );
			
			if (bb == null)
			{
				removedPairs.add( pairs.get( i ) );
				pairs.remove( i );
			}
			
		}
		
		return removedPairs;
		
	}
	
	public static <V extends ViewId> ArrayList< PairwiseStitchingResult< ViewId > > computePairsLK(
			final List< Pair< Group< V >, Group< V > > > pairs, final LucasKanadeParameters params,
			final ViewRegistrations vrs,
			final AbstractSequenceDescription< ?, ? extends BasicViewDescription< ? >, ? > sd,
			final GroupedViewAggregator gva, final long[] downsamplingFactors,
			final ProgressWriter progressWriter)
	{
		final ArrayList< Callable< Pair< Pair< Group< V >, Group< V > >, Pair< Pair< AffineGet, Double >, RealInterval > > > > tasks = new ArrayList<>();

		// remove non-overlapping comparisons
		final List< Pair< Group< V >, Group< V > > > removedPairs = filterNonOverlappingPairs( pairs, vrs, sd );
		//removedPairs.forEach( p -> IOFunctions.println( "Skipping non-overlapping pair: " + p.getA() + " -> " + p.getB() ) );
		IOFunctions.println( "Skipping " + removedPairs.size() + " non-overlapping pairs.");

		final int nComparisions = pairs.size();
		AtomicInteger nCompleted = new AtomicInteger();

		IJ.showProgress( 0.0 );

		for ( final Pair< Group< V >, Group< V > > p : pairs )
		{
			tasks.add(
					new Callable< Pair< Pair< Group< V >, Group< V > >, Pair< Pair< AffineGet, Double >, RealInterval > > >()
					{
						@Override
						public Pair< Pair< Group< V >, Group< V > >, Pair< Pair< AffineGet, Double >, RealInterval > > call()
								throws Exception
						{
							Pair< Pair< AffineGet, Double >, RealInterval > result = null;

							IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Compute pairwise: "
									+ p.getA() + " <> " + p.getB() );

							final ExecutorService serviceLocal = Executors.newFixedThreadPool(
									Math.max( 2, Runtime.getRuntime().availableProcessors() / 4 ) );

							final ViewId firstVdA = p.getA().iterator().next();
							final ViewId firstVdB = p.getB().iterator().next();

							boolean nonTranslationsEqual = TransformTools.nonTranslationsEqual(
									vrs.getViewRegistration( firstVdA ), vrs.getViewRegistration( firstVdB ) );

							if ( nonTranslationsEqual )
							{

								result = computeStitchingLucasKanade( p.getA(), p.getB(), vrs, params, sd, gva,
										downsamplingFactors, serviceLocal );
							}
							else
							{
								result = computeStitchingNonEqualTransformationsLucasKanade( p.getA(), p.getB(), vrs, params, sd,
										gva, downsamplingFactors, serviceLocal );
							}

							serviceLocal.shutdown();

							int nCompletedI = nCompleted.incrementAndGet();
							if (progressWriter != null)							
								progressWriter.setProgress( (double) nCompletedI / nComparisions );

							if ( result != null )
								IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Compute pairwise: "
										+ p.getA() + " <> " + p.getB() + ": r=" + result.getA().getB() );

							return new ValuePair<>( p, result );
						}
					} );
		}

		final ArrayList< PairwiseStitchingResult< ViewId > > results = new ArrayList<>();

		// set up executor service
		final int batchSize = params.manualNumTasks ? params.numTasks : Math.max( 2, Threads.numThreads() / 6 );
		final ExecutorService serviceGlobal = Executors.newFixedThreadPool( batchSize );

		try
		{
			for ( final Future< Pair< Pair< Group< V >, Group< V > >, Pair< Pair< AffineGet, Double >, RealInterval > > > future : serviceGlobal
					.invokeAll( tasks ) )
			{
				// wait for task to complete
				final Pair< Pair< Group< V >, Group< V > >, Pair< Pair< AffineGet, Double >, RealInterval > > result = future
						.get();

				if ( result.getB() == null )
					continue;

				AffineTransform3D resT = new AffineTransform3D();
				resT.preConcatenate( result.getB().getA().getA() );

				// TODO: can we get rid of this ugly cast
				Group< ViewId > groupA = new Group< ViewId >( result.getA().getA().getViews().stream()
						.map( x -> (ViewId) x ).collect( Collectors.toList() ) );
				Group< ViewId > groupB = new Group< ViewId >( result.getA().getB().getViews().stream()
						.map( x -> (ViewId) x ).collect( Collectors.toList() ) );

				
				final double oldTransformHash = PairwiseStitchingResult.calculateHash(
						vrs.getViewRegistration( groupA.getViews().iterator().next() ),
						vrs.getViewRegistration( groupB.getViews().iterator().next() ) );
				// TODO: when does that really happen?
				if ( result.getB() != null )
					results.add( new PairwiseStitchingResult<>( new ValuePair<>( groupA, groupB ), result.getB().getB(),
							resT, result.getB().getA().getB(), oldTransformHash ) );
			}
		}
		catch ( final Exception e )
		{
			IOFunctions.println( new Date( System.currentTimeMillis() ) + " Failed to compute pairwise shift: " + e );
			e.printStackTrace();
			return null;
		}

		return results;
	}
	
	public static <V extends ViewId > ArrayList< PairwiseStitchingResult<ViewId> > computePairs( 	final List< Pair<  Group< V >,  Group< V > > > pairs, 
																		final PairwiseStitchingParameters params, 
																		final ViewRegistrations vrs,
																		final AbstractSequenceDescription< ?, ? extends BasicViewDescription< ? >, ? > sd, 
																		final GroupedViewAggregator gva,
																		final long[] downsamplingFactors)
	{

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Identifying overlapping image pairs ..." );

		final ArrayList< Callable< Pair< Pair< Group< V >, Group< V > >, Pair<Pair< AffineGet, Double >, RealInterval> > > > tasks = new ArrayList<>();

		// remove non-overlapping comparisons
		final List< Pair< Group< V >, Group< V > > > removedPairs = filterNonOverlappingPairs( pairs, vrs, sd );
		//removedPairs.forEach( p -> System.out.println( "Skipping non-overlapping pair: " + p.getA() + " -> " + p.getB() ) );

		final int nComparisions = pairs.size();
		final AtomicInteger nCompleted = new AtomicInteger();
		final Random rnd = new Random( 345 );
		final double rndThres = (double)maxNumOutputLines / (double)nComparisions;

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " + removedPairs.size() + " non-overlapping view-pairs for computing." );

		if ( nComparisions > maxNumOutputLines )
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Total number of comparisons: " + nComparisions + " (this is a lot, logging results in ImageJ log only for ~"+maxNumOutputLines+" randomly selected ones.");
		else
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Total number of comparisons: " + nComparisions );

		IJ.showProgress( 0.0 );

		// how many pairs of Phase Correlations we run in parallel
		// it should not be more than max(Threads.numThreads() / 2, 1)
		// so we can dedicate two threads per PCM pair
		final int batchSize = 
				Math.min(
						Math.max( 1, Threads.numThreads() / 2 ), // Threads.numThreads() could be 1
						params.manualNumTasks ? params.numTasks : Math.max( 2, Threads.numThreads() / 6 ) );

		for ( final Pair< Group< V >, Group< V > > p : pairs )
		{
			tasks.add( new Callable< Pair< Pair< Group< V >, Group< V > >, Pair<Pair< AffineGet, Double >, RealInterval> > >()
			{
				@Override
				public Pair< Pair< Group< V >, Group< V > >, Pair<Pair< AffineGet, Double >, RealInterval> > call() throws Exception
				{
					Pair<Pair< AffineGet, Double >, RealInterval> result = null;

					final int numLocalThreads = Threads.numThreads() / batchSize; //Math.max( 2, Threads.numThreads() / 4 );
					final ExecutorService serviceLocal = Executors.newFixedThreadPool( numLocalThreads );

					// TODO: do non-equal transformation registration when views within a group have differing transformations
					final ViewId firstVdA = p.getA().iterator().next();
					final ViewId firstVdB = p.getB().iterator().next();
					
					boolean nonTranslationsEqual = TransformTools.nonTranslationsEqual( vrs.getViewRegistration( firstVdA ), vrs.getViewRegistration( firstVdB ) );
					
					if (nonTranslationsEqual)
					{
						if ( PairwiseStitching.debug )
							System.out.println( "non translations equal" );
						result = computeStitching(
								p.getA(),
								p.getB(),
								vrs,
								params,
								sd,
								gva,
								downsamplingFactors,
								serviceLocal );
					}
					else
					{
						result = computeStitchingNonEqualTransformations( 
								p.getA(),
								p.getB(),
								vrs,
								params,
								sd,
								gva,
								downsamplingFactors,
								serviceLocal );
						if ( PairwiseStitching.debug )
							System.out.println( "non translations NOT equal, using virtually fused views for stitching" );
					}

					serviceLocal.shutdown();

					// show progress in ImageJ progress bar (TODO: should we really do this here or leave it GUI-independent?)
					int nCompletedI = nCompleted.incrementAndGet();
					IJ.showProgress( (double) nCompletedI / nComparisions );

					if ( nComparisions <= maxNumOutputLines || rnd.nextDouble() < rndThres )
					{
						if (result != null)
							IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Compute pairwise: " + p.getA() + " <> " + p.getB() + ": r=" + result.getA().getB() );
						else
							IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Compute pairwise: " + p.getA() + " <> " + p.getB() + ": No shift found." );
					}

					return new ValuePair<>( p,  result );
				}
			});
		}

		final ArrayList< PairwiseStitchingResult< ViewId > > results = new ArrayList<>();

		final ExecutorService serviceGlobal = Executors.newFixedThreadPool( batchSize );

		IOFunctions.println( "Computing overlap for: " + batchSize + " pairs of images at once, each " + (Threads.numThreads() / batchSize) + " threads, (in total <=" + Threads.numThreads() + " threads)" );
		IOFunctions.println( "Total num CPUs: " + Runtime.getRuntime().availableProcessors() );

		try
		{
			for ( final ArrayList< Callable< Pair< Pair< Group< V >, Group< V > >, Pair<Pair< AffineGet, Double >, RealInterval> > > > part : Threads.splitTasks( tasks, batchSize ) )
				for ( final Future< Pair< Pair< Group< V >, Group< V > >, Pair<Pair< AffineGet, Double >, RealInterval> > > future : serviceGlobal.invokeAll( part ) )
				{
				// wait for task to complete
				final Pair< Pair< Group< V >, Group< V > >, Pair<Pair< AffineGet, Double >, RealInterval> > result = future.get();

				if (result.getB() == null)
					continue;
				
				/*
				final ViewRegistration vrA = vrs.getViewRegistration( result.getA().getA().iterator().next() );
				final ViewRegistration vrB = vrs.getViewRegistration( result.getA().getB().iterator().next() );
				
				// get non-translation transform between the initial location of groupA
				Pair< AffineGet, TranslationGet > initialTransformsA = TransformTools.getInitialTransforms( vrA, false, new AffineTransform3D() );

				// apply to shift vector
				// FIXME: this only works for scaling, we need to do something different about rotations, etc.
				boolean nonTranslationsEqual = TransformTools.nonTranslationsEqual(vrA, vrB);
				if (nonTranslationsEqual)
					initialTransformsA.getA().apply( result.getB().getA().getA(), result.getB().getA().getA() );

				 */
				
				AffineTransform3D resT = new AffineTransform3D();
				resT.preConcatenate( result.getB().getA().getA() );

				// TODO: can we get rid of this ugly cast
				Group< ViewId > groupA = new Group<ViewId>(result.getA().getA().getViews().stream().map( x -> (ViewId) x ).collect( Collectors.toList() ));
				Group< ViewId > groupB = new Group<ViewId>(result.getA().getB().getViews().stream().map( x -> (ViewId) x ).collect( Collectors.toList() ));

				// TODO: when does that really happen?
				if ( result.getB() != null)
				{
					final double oldTransformHash = PairwiseStitchingResult.calculateHash(
							vrs.getViewRegistration( groupA.getViews().iterator().next() ),
							vrs.getViewRegistration( groupB.getViews().iterator().next() ) );

					results.add( new PairwiseStitchingResult<>( new ValuePair<>(groupA, groupB), result.getB().getB(),  resT, result.getB().getA().getB(), oldTransformHash ) );
				}
			}
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to compute pairwise shift: " + e );
			e.printStackTrace();
			return null;
		}

		return results;
	}

	public static void main( String[] args )
	{
		final SpimData2 d = GenerateSpimData.grid3x2();
		final SequenceDescription sd = d.getSequenceDescription();

		// select views to process
		final List< ViewId > rawViewIds = new ArrayList< ViewId >();
		rawViewIds.addAll( sd.getViewDescriptions().keySet() );
		Collections.sort( rawViewIds );

		// take together all views where the all attributes are the same except channel (i.e. group the channels)
		final List< Group<ViewId> > viewIds = Group.groupByChannel( rawViewIds, sd );

		// define fixed tiles
		final ArrayList< ViewId > fixedViews = new ArrayList< ViewId >();
		fixedViews.addAll( viewIds.get( 0 ).getViews() );

		final long[] downsamplingFactors = new long[] {2,2,1};

		final List<Pair<Group<ViewId>, Group<ViewId>>> pairs = new ArrayList<>();
		for (int i = 0; i < viewIds.size(); i++)
			for (int j = i+1; j< viewIds.size(); j++)
				pairs.add( new ValuePair<>( viewIds.get( i ), viewIds.get( j ) ) );		
		
		final GroupedViewAggregator gva = new GroupedViewAggregator();
		gva.addAction( ActionType.AVERAGE, Channel.class, null );
		
		// compute pairwise shifts
		final ArrayList< PairwiseStitchingResult <ViewId>> results = computePairs( pairs,
																new PairwiseStitchingParameters(), 
																d.getViewRegistrations(),
																d.getSequenceDescription() ,
																gva,
																downsamplingFactors);

		results.forEach( r -> System.out.println( r.getTransform() ) );
		
		for ( final ViewId v : fixedViews )
			System.out.println( "Fixed: " + v );

		GlobalOpt.computeTiles( 
				new TranslationModel3D(),
				true,// preAlign
				new ImageCorrelationPointMatchCreator( results, 0.5 ),
				new ConvergenceStrategy( 5.0 ),
				fixedViews,
				viewIds );


	}
}
