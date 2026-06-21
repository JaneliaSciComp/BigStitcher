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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseStitchingResult;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.gui.StitchingExplorerPanel;
import net.preibisch.stitcher.gui.overlay.DemoLinkOverlay;

public class FilteredStitchingResults 
{
	public static interface Filter
	{
		public <C extends Comparable< C >> boolean conforms(final PairwiseStitchingResult< C > result);
	}

	public static class CorrelationFilter implements Filter
	{
		private final double minR;
		private final double maxR;
		public CorrelationFilter(final double minR, final double maxR)
		{
			this.minR = minR;
			this.maxR = maxR;
		}

		@Override
		public <C extends Comparable< C >> boolean conforms(final PairwiseStitchingResult< C > result)
		{
			return (result.r() <= maxR) && (result.r() >= minR);
		}
	}

	public static class AbsoluteShiftFilter implements Filter
	{
		private final double[] minMaxShift;
		public AbsoluteShiftFilter(double[] minMaxShift)
		{
			this.minMaxShift = minMaxShift;
		}

		@Override
		public <C extends Comparable< C >> boolean conforms(final PairwiseStitchingResult< C > result)
		{
			double[] v = new double[result.getTransform().numDimensions()];
			result.getTransform().apply( v, v );

			// negative means at least that shift, positive means less than this shift is allowed
			for (int d = 0; d < v.length; d++)
			{
				if ( minMaxShift[d] >= 0 && Math.abs( v[d] ) > minMaxShift[d] )
					return false;
				if ( minMaxShift[d] < 0 && Math.abs( v[d] ) < -minMaxShift[d] )
					return false;
			}
			return true;
		}
	}

	public static class ShiftMagnitudeFilter implements Filter
	{
		private final double maxShift;

		public ShiftMagnitudeFilter(double maxShift)
		{
			this.maxShift = maxShift;
		}

		@Override
		public <C extends Comparable< C >> boolean conforms(PairwiseStitchingResult< C > result)
		{
			double[] v = new double[result.getTransform().numDimensions()];
			double[] vt = new double[result.getTransform().numDimensions()];
			result.getTransform().apply( v, vt );

			return Util.distance( new RealPoint( v ), new RealPoint( vt ) ) <= maxShift;
		}
	}

	private Map< Pair< Group< ViewId >, Group< ViewId > >, PairwiseStitchingResult< ViewId > > filteredPairwiseResults;
	private DemoLinkOverlay demoOverlay; // can be null
	private StitchingResults wrapped;
	private List<Filter> filters;

	public FilteredStitchingResults(StitchingResults wrapped )
	{
		this( wrapped, null );
	}

	public FilteredStitchingResults(StitchingResults wrapped, DemoLinkOverlay demoOverlay )
	{
		this.wrapped = wrapped;
		this.demoOverlay = demoOverlay;
		filteredPairwiseResults = new HashMap<>();
		filters = new ArrayList<>();
		updateFilteredResults();
	}

	void updateFilteredResults()
	{
		filteredPairwiseResults.clear();
		wrapped.getPairwiseResults().forEach( (k, v) -> 
		{
			for (Filter filter : filters)
				if (!filter.conforms(v))
					return;
			filteredPairwiseResults.put( k, v );
		});
	}

	public void clearFilter(Class<? extends Filter> filterClass)
	{
		// clear previous instances
		for (int i = filters.size() - 1; i >= 0; i--)
		{
			if (filters.get( i ).getClass().isAssignableFrom( filterClass ))
				filters.remove( i );
		}
		updateFilteredResults();
	}

	public void addFilter(Filter filter)
	{
		// remove existing instance
		clearFilter( filter.getClass() );
		filters.add( filter );
		updateFilteredResults();
	}

	public void applyToWrappedSubset( Collection< Pair< Group< ViewId >, Group< ViewId > > > targets)
	{
		final Map< Pair< Group< ViewId >, Group< ViewId > >, PairwiseStitchingResult< ViewId > > filteredTmp = new HashMap<>();
		filteredTmp.putAll( wrapped.getPairwiseResults() );

		if ( demoOverlay != null )
			demoOverlay.getFilteredResults().clear();

		wrapped.getPairwiseResults().forEach( (k, v) -> 
		{
			if (!targets.contains( k ))
				return;
			for (Filter filter : filters)
				if (!filter.conforms(v))
				{
					filteredTmp.remove( k );
					if ( demoOverlay != null )
						demoOverlay.getFilteredResults().add( k );
				}
		});

		wrapped.getPairwiseResults().clear();
		wrapped.getPairwiseResults().putAll( filteredTmp );
	}

	public void applyToWrappedAll()
	{
		applyToWrappedSubset( wrapped.getPairwiseResults().keySet() );
	}

	public Map< Pair< Group< ViewId >, Group< ViewId > >, PairwiseStitchingResult< ViewId > > getPairwiseResults()
	{
		return filteredPairwiseResults;
	}

	public static void main(String[] args)
	{
		final Pair<Group<ViewId>, Group<ViewId>> pair12 = new ValuePair<>( new Group<>(new ViewId( 0, 1 )), new Group<> (new ViewId( 0, 2 )) );
		final AffineTransform3D tr12 = new AffineTransform3D().preConcatenate( new Translation3D( 10.0, 0.0, 0.0 ) );
		final double r12 = .5;		
		final PairwiseStitchingResult< ViewId > psr12 = new PairwiseStitchingResult<>( pair12, null, tr12, r12, 0.0 );

		final Pair<Group<ViewId>, Group<ViewId>> pair13 = new ValuePair<>( new Group<>(new ViewId( 0, 1 )), new Group<> (new ViewId( 0, 3 )) );
		final AffineTransform3D tr13 = new AffineTransform3D().preConcatenate( new Translation3D( 50.0, 0.0, 0.0 ) );
		final double r13 = 1.0;		
		final PairwiseStitchingResult< ViewId > psr13 = new PairwiseStitchingResult<>( pair13, null, tr13, r13, 0.0 );

		final StitchingResults sr = new StitchingResults();
		sr.setPairwiseResultForPair( pair12, psr12 );
		sr.setPairwiseResultForPair( pair13, psr13 );

		final FilteredStitchingResults fsr = new FilteredStitchingResults( sr );

		System.out.println( "#nofilter" );
		fsr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

		fsr.addFilter( new CorrelationFilter( 0.9, 1.0 ) );		
		System.out.println( "corr filter" );
		fsr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

		fsr.addFilter( new CorrelationFilter( 0.4, 1.0 ) );		
		System.out.println( "updated corr filter" );
		fsr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

		fsr.addFilter( new CorrelationFilter( 0.9, 1.0 ) );
		fsr.clearFilter( CorrelationFilter.class );
		System.out.println( "add and remove corr filter" );
		fsr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

		fsr.addFilter( new AbsoluteShiftFilter( new double[] {20.0, Double.MAX_VALUE, Double.MAX_VALUE} ) );
		System.out.println( "absolut shift filter" );
		fsr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

		fsr.clearFilter( AbsoluteShiftFilter.class );
		System.out.println( "cleared" );
		fsr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

		fsr.addFilter( new ShiftMagnitudeFilter( 20.0 ) );
		System.out.println( "shift magnitude filter" );
		fsr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

		System.out.println( " --- " );

		System.out.println( "wrapped" );
		sr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

		Set<Pair<Group<ViewId>, Group<ViewId>>> wrongSubset = new HashSet<>();
		wrongSubset.add( pair12 );
		fsr.applyToWrappedSubset( wrongSubset );

		System.out.println( "wrapped, apply to wrong subset -> should not change anything" );
		sr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

		fsr.applyToWrappedAll();
		System.out.println( "wrapped, apply filter to all results" );
		sr.getPairwiseResults().forEach( (k, v) -> System.out.println( k.getA() + " - " + k.getB() ) );

	}

}
