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
package net.preibisch.stitcher.algorithm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ij.ImageJ;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.deconvolution.normalization.AdjustInput;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class GroupedViewAggregator
{
	private final List<Action> actions;

	public GroupedViewAggregator()
	{
		this.actions = new ArrayList<>();
	}

	public class Action {
		ActionType actionType;
		final List<Class<? extends Entity>> entityClasses;
		final List<Entity> instances;

		Action(ActionType at, Class<? extends Entity> entityClass, Entity instance){
			this.actionType = at;
			this.entityClasses = new ArrayList<>();
			entityClasses.add( entityClass );
			this.instances = new ArrayList<>();
			instances.add( instance );
		}

		public <T extends RealType<T>> Map<BasicViewDescription<?>, RandomAccessibleInterval<T>> aggregate(
				Map<BasicViewDescription< ? >, RandomAccessibleInterval<T>> input)
		{
			Map<BasicViewDescription<?>, RandomAccessibleInterval<T>> res = new HashMap<>();

			if (actionType == ActionType.PICK_SPECIFIC)
				res = pickSpecific(input);
			else if (actionType == ActionType.PICK_BRIGHTEST)
				res = pickBrightest(input);
			else //if (actionType == ActionType.AVERAGE)
				res = average(input);

			return res;
		}

		public <T extends RealType<T>> Map<BasicViewDescription<?>, RandomAccessibleInterval<T>> pickBrightest(Map<BasicViewDescription< ? >, RandomAccessibleInterval<T>> input)
		{

			final HashMap< BasicViewDescription<?>, RandomAccessibleInterval<T>> res = new HashMap<>();

			// combine by all classes for which we take the same action
			// e.g. pick brightest by channels & illums -> combine by those
			final Set<Class<? extends Entity>> groupingSet = new HashSet<>();
			groupingSet.addAll( entityClasses );
			final List< Group< BasicViewDescription< ? > > > grouped = 
						Group.combineBy( new ArrayList<>(input.keySet()), groupingSet );

			for (Group< BasicViewDescription< ? > > g : grouped)
			{
				final List<BasicViewDescription< ? >> vds = new ArrayList<>();
				final List<RandomAccessibleInterval<T>> rais = new ArrayList<>();
				for (BasicViewDescription< ? > vd : g)
				{
					vds.add( vd );
					rais.add(input.get( vd ));
				}

				// quick check if we have only one present image 
				// -> no need to look at the images in that case
				int nonNullCount = 0;
				int nonNullIndex = -1;
				for (int i = 0; i < rais.size(); i++){
					if (rais.get( i ) != null)
						nonNullCount += 1;
						nonNullIndex = i;
				}
				if (nonNullCount == 1)
				{
					res.put( vds.get( nonNullIndex ), rais.get( nonNullIndex ) );
					continue;
				}

				Double max = -Double.MAX_VALUE;
				int maxIdx = -1;
				for (int i = 0; i < rais.size(); i++){

					// a view is missing, do not take it into account
					if (rais.get( i ) == null)
						continue;

					IterableInterval< T > iterableImg = Views.iterable( rais.get( i ) );
					double mean = AdjustInput.sumImg( iterableImg ) / (double)iterableImg.size();
					if (mean > max)
					{
						max = mean;
						maxIdx = i;
					}
				}

				// at least one view (actually two, since we handle that special case above) is present
				if (maxIdx >= 0)
				{
					res.put( vds.get( maxIdx ), rais.get( maxIdx ) );
				}
			}
			return res;
		}

		public <T extends RealType<T>> Map<BasicViewDescription<?>, RandomAccessibleInterval<T>> pickSpecific(Map<BasicViewDescription< ? >, RandomAccessibleInterval<T>> input)
		{
			final HashMap< BasicViewDescription<?>, RandomAccessibleInterval<T>> res = new HashMap<>();

			// combine by all classes for which we take the same action
			// e.g. pick specific channels & illums combination -> combine by those
			final Set<Class<? extends Entity>> groupingSet = new HashSet<>();
			groupingSet.addAll( entityClasses );
			final List< Group< BasicViewDescription< ? > > > grouped = 
						Group.combineBy( new ArrayList<>(input.keySet()), groupingSet );

			for (Group< BasicViewDescription< ? > > g : grouped)
			{
				final List<BasicViewDescription< ? >> vds = new ArrayList<>();
				final List<RandomAccessibleInterval<T>> rais = new ArrayList<>();
				for (BasicViewDescription< ? > vd : g)
				{
					vds.add( vd );
					rais.add(input.get( vd ));
				}
				for (int i = 0; i< vds.size(); i++)
				{
					// check if all entities match and the view is present
					boolean mismatch = false;
					for (int j = 0; j<entityClasses.size(); j++)
					{
						final Class<? extends Entity> entityClass = entityClasses.get( j );
						if (entityClass == TimePoint.class)
						{
							if (!vds.get( i ).getTimePoint().equals( instances.get( j )) || !(vds.get( i ).isPresent()))
							{
								mismatch = true;
								break;
							}
						}
						else if (!vds.get( i ).getViewSetup().getAttribute( entityClass ).equals( instances.get( j ) ) || !(vds.get( i ).isPresent()))
						{
							mismatch = true;
							break;
						}
					}
					if (!mismatch)
						res.put( vds.get( i ), rais.get( i ) );
				}
			}

			return res;
		}

		public <T extends RealType<T>> Map<BasicViewDescription<?>, RandomAccessibleInterval<T>> average(Map<BasicViewDescription< ? >, RandomAccessibleInterval<T>> input)
		{

			// only one view left -> nothing to average
			if (input.size() == 1)
				return input;

			final HashMap< BasicViewDescription<?>, RandomAccessibleInterval<T>> res = new HashMap<>();

			// combine by all classes for which we take the same action
			// e.g. pick brightest by channels & illums -> combine by those
			final Set<Class<? extends Entity>> groupingSet = new HashSet<>();
			groupingSet.addAll( entityClasses );
			final List< Group< BasicViewDescription< ? > > > grouped = 
									Group.combineBy( new ArrayList<>(input.keySet()), groupingSet );

			for (Group< BasicViewDescription< ? > > g : grouped)
			{
				final List<BasicViewDescription< ? >> vds = new ArrayList<>();
				final List<RandomAccessibleInterval<T>> rais = new ArrayList<>();
				for (BasicViewDescription< ? > vd : g)
				{
					vds.add( vd );
					rais.add(input.get( vd ));
				}

				// quick check if we have only one present image 
				// -> no need to look at the images in that case
				int nonNullCount = 0;
				int nonNullIndex = -1;
				for (int i = 0; i < rais.size(); i++){
					if (rais.get( i ) != null)
						nonNullCount += 1;
						nonNullIndex = i;
				}
				if (nonNullCount == 1)
				{
					res.put( vds.get( nonNullIndex ), rais.get( nonNullIndex ) );
					continue;
				}

				AveragedRandomAccessible< T > avg = null;
				int nPresent = 0;
				int firstNonNull = -1;
	
				for (int i = 0; i< rais.size(); i++)
				{
					if (rais.get( i ) == null)
						continue;
	
					if (avg == null)
					{
						avg = new AveragedRandomAccessible<>( rais.get( i ).numDimensions() );
						firstNonNull = i;
					}
	
					RandomAccessibleInterval< T > zerod = Views.zeroMin( rais.get( i ));
					avg.addRAble( Views.extendZero( zerod ) );
					nPresent++;
				}
	
				if (nPresent > 0)
				{
					RandomAccessibleInterval< T > zerod = Views.zeroMin( rais.get( firstNonNull ));
					res.put( vds.get( 0 ), Views.interval( avg, zerod ) );
				}
			}
			return res;
		}

	}

	public enum ActionType {
		PICK_SPECIFIC, PICK_BRIGHTEST, AVERAGE
	}

	public void addAction(ActionType at, Class<? extends Entity> entityClass, Entity instance)
	{
		// check if we already have an Action of the same type
		Action existingAction = null;
		for (final Action ac : actions)
			if ( ac.actionType.equals( at ) )
			{
				existingAction = ac;
				break;
			}

		// No -> create new action
		if (existingAction == null)
		{
			final Action newAc = new Action( at, entityClass, instance );
			actions.add(newAc);
	
			// sort to enforce pick specific -> pick brightest -> average order
			Collections.sort(actions, new Comparator<Action>()
			{
				@Override
				public int compare(Action o1, Action o2)
				{
					return o1.actionType.compareTo( o2.actionType );
				}
			});
		}
		// Yes -> add entity (class) to existing Action
		else
		{
			existingAction.entityClasses.add( entityClass );
			existingAction.instances.add( instance );
		}
	}

	public <T extends RealType<T>> RandomAccessibleInterval< T > aggregate(
			List<RandomAccessibleInterval< T >> rais,
			List<? extends ViewId> vids,
			AbstractSequenceDescription< ?, ? extends BasicViewDescription< ? >, ? > sd
			)
	{
		Map<BasicViewDescription< ? >, RandomAccessibleInterval<T>> map = new HashMap<>();

		for (int i = 0; i < vids.size(); i++)
		{
			ViewId vid = vids.get( i );
			BasicViewDescription< ? > vd = sd.getViewDescriptions().get( vid );
			map.put( vd, rais.get( i ) );
		}

		for (final Action action : actions)
		{
			map = action.aggregate( map );
		}

		// return the first RAI still present
		// ideally, there should be only one left
		return map.values().iterator().next();
		
	}

	public <T extends RealType<T>> RandomAccessibleInterval< T > aggregate(Group<? extends ViewId> gv, 
												AbstractSequenceDescription< ?, ? extends BasicViewDescription< ? >, ? > sd,
												long[] downsampleFactors,
												final AffineTransform3D dsCorrectionT,
												final ExecutorService service){

		Map<BasicViewDescription< ? >, RandomAccessibleInterval<T>> map = new HashMap<>();
		boolean dsAdjusted = false;

		for (ViewId vid : gv.getViews())
		{
			BasicViewDescription< ? > vd = sd.getViewDescriptions().get( vid );

			final RandomAccessibleInterval< T > rai;

			// if view is not present, add null as the RAIProxy
			if ( vd.isPresent() )
			{
				rai = new RAIProxy< T >( sd.getImgLoader(), vid, downsampleFactors );

				if ( !dsAdjusted )
				{
					// we only get the transformation for downsampling once (could be three channels averaged here)
					dsCorrectionT.set( DownsampleTools.getMipMapTransform( sd.getImgLoader(), vid, downsampleFactors) );
					//DownsampleTools.openAndDownsample( sd.getImgLoader(), vid, dsCorrectionT, downsampleFactors, true, false );
					dsAdjusted = true;
				}
			}
			else
			{
				rai = null;
			}

			map.put( vd, rai );
		}

		for (Action action : actions)
		{
			map = action.aggregate( map );
			// we filtered out all the views
			if (map.size() < 1)
				return null;
		}

		// return the first RAI still present
		// ideally, there should be only one left - more than one means that the actions were not right, e.g.
		// we have 3 channels and 2 illuminations and the actions only state to average channels
		return map.values().iterator().next();
		
	}
	
	public static void main(String[] args)
	{

		final ArrayList< ViewSetup > setups = new ArrayList< ViewSetup >();
		final ArrayList< ViewRegistration > registrations = new ArrayList< ViewRegistration >();

		final Channel c0 = new Channel( 0, "RFP" );
		final Channel c1 = new Channel( 1, "YFP" );

		final Angle a0 = new Angle( 0 );
		final Illumination i0 = new Illumination( 0 );
		final Illumination i1 = new Illumination( 1 );		

		final Tile t0 = new Tile( 0, "Tile0", new double[]{ 0.0, 0.0, 0.0 } );
		
		final Dimensions d0 = new FinalDimensions( 512l, 512l, 1l );
		final VoxelDimensions vd0 = new FinalVoxelDimensions( "px", 0.4566360, 0.4566360, 2.0000000 );

		setups.add( new ViewSetup( 0, "setup 0", d0, vd0, t0, c0, a0, i0 ) );
		setups.add( new ViewSetup( 1, "setup 1", d0, vd0, t0, c1, a0, i0 ) );
		setups.add( new ViewSetup( 2, "setup 2", d0, vd0, t0, c0, a0, i1 ) );
		setups.add( new ViewSetup( 3, "setup 3", d0, vd0, t0, c1, a0, i1 ) );

		final ArrayList< TimePoint > t = new ArrayList< TimePoint >();
		t.add( new TimePoint( 0 ) );
		final TimePoints timepoints = new TimePoints( t );
		
		final ArrayList< ViewId > missing = new ArrayList< ViewId >();
		missing.add( new ViewId(0,0) );
		final MissingViews missingViews = new MissingViews( missing );

		final ImgLoader imgLoader = new ImgLoader()
		{
			@Override
			public SetupImgLoader< ? > getSetupImgLoader( final int setupId )
			{
				return new SetupImgLoader<UnsignedShortType>()
				{

					@Override
					public RandomAccessibleInterval< UnsignedShortType > getImage(int timepointId,
							ImgLoaderHint... hints)
					{
						Img< UnsignedShortType > raiout = new ArrayImgFactory<UnsignedShortType>().create( d0, new UnsignedShortType() );
						for (UnsignedShortType t : raiout)
							t.set( setupId );
						return raiout;
						
					}

					@Override
					public UnsignedShortType getImageType(){return new UnsignedShortType();}

					@Override
					public RandomAccessibleInterval< FloatType > getFloatImage(int timepointId, boolean normalize,
							ImgLoaderHint... hints)
					{
						Img< FloatType > raiout = new ArrayImgFactory<FloatType>().create( d0, new FloatType() );
						for (FloatType t : raiout)
							t.set( setupId );
						return raiout;
					}

					@Override
					public Dimensions getImageSize(int timepointId){return d0;}
					@Override
					public VoxelDimensions getVoxelSize(int timepointId){return vd0;}
					
				};
			}
		};
		
		for ( final ViewSetup vs : setups )
		{
			final ViewRegistration vr = new ViewRegistration( t.get( 0 ).getId(), vs.getId() );
			registrations.add( vr );
		}

		final SequenceDescription sd = new SequenceDescription( timepoints, setups, imgLoader, missingViews );

		final GroupedViewAggregator gva = new GroupedViewAggregator();
		//gva.addAction( ActionType.PICK_SPECIFIC, Illumination.class, new Illumination( 0 ) );
		//gva.addAction( ActionType.PICK_SPECIFIC, Illumination.class, new Illumination( 1 ) );
		gva.addAction( ActionType.AVERAGE, Illumination.class, null );
		gva.addAction( ActionType.PICK_BRIGHTEST, Channel.class, null );
		
		List<ViewId> setupsVID = new ArrayList<>();
		setupsVID.add( new ViewId(0,0) );
		setupsVID.add( new ViewId(0,1) );
		setupsVID.add( new ViewId(0,2) );
		setupsVID.add( new ViewId(0,3) );
		Group<ViewId> gv = new Group<>( setupsVID );

		final ExecutorService taskExecutor = Executors.newFixedThreadPool( Threads.numThreads() );

		RandomAccessibleInterval< FloatType > res = (RandomAccessibleInterval< FloatType >) gva.aggregate( gv, sd, new long[] {1,1,1} , new AffineTransform3D(), taskExecutor );
		System.out.println( Views.iterable( res ).firstElement().getClass() );
		if (res != null)
			ImageJFunctions.show( res );

		taskExecutor.shutdown();
		new ImageJ();
		
	}
	

}
