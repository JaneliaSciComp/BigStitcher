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
package net.preibisch.stitcher.gui;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.swing.table.AbstractTableModel;

import ij.IJ;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.algorithm.FilteredStitchingResults;
import net.preibisch.stitcher.gui.overlay.DemoLinkOverlay;

public class LinkExplorerTableModel extends AbstractTableModel implements StitchingResultsSettable
{

	
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 3972623555571460757L;

	public List< Pair< Group<ViewId>, Group<ViewId> > > getActiveLinks()
	{
		return activeLinksAfterFilter;
	}

	@Override
	public String getColumnName(int column)
	{
		return new String[]{"ViewIDs A", "ViewIDs B", "cross correlation", "shift"}[column];
	}


	private List<Pair<Group<ViewId>, Group<ViewId>>> activeLinksBeforeFilter;
	private List<Pair<Group<ViewId>, Group<ViewId>>> activeLinksAfterFilter;
	
	private StitchingResults results;
	private FilteredStitchingResults filteredResults;
	private DemoLinkOverlay demoLinkOverlay;
	
	public StitchingResults getStitchingResults() { return results; }
	
	public LinkExplorerTableModel( final DemoLinkOverlay demoOverlay )
	{
		this.demoLinkOverlay = demoOverlay;

		activeLinksBeforeFilter = new ArrayList<>();
		activeLinksAfterFilter = new ArrayList<>();
	}
	
	public void setActiveLinks(List<Pair<Group<ViewId>, Group<ViewId>>> links)
	{
		activeLinksBeforeFilter.clear();
		activeLinksAfterFilter.clear();
		links.forEach( l ->  {
			activeLinksBeforeFilter.add( l );
			if (filteredResults.getPairwiseResults().keySet().contains( l ))
				activeLinksAfterFilter.add( l );
		});
	}
	
	public FilteredStitchingResults getFilteredResults()
	{
		return filteredResults;
	}
	
	@Override
	public int getRowCount()
	{
		return activeLinksAfterFilter == null ? 0 : activeLinksAfterFilter.size();
	}

	@Override
	public int getColumnCount()
	{
		return 4;
	}

	@Override
	public Object getValueAt(int rowIndex, int columnIndex)
	{
		if (columnIndex == 0)
		{
			/*
			IJ.log( "" + new Date( System.currentTimeMillis() ) );
			IJ.log( "filteredResults: " + filteredResults );
			IJ.log( "activeLinksAfterFilter: " + activeLinksAfterFilter.size() );
			IJ.log( "fetching activeLinksAfterFilter: i="+rowIndex + "=" + activeLinksAfterFilter.get( rowIndex ) );
			Group<ViewId> a = activeLinksAfterFilter.get( rowIndex ).getA();
			Group<ViewId> b = activeLinksAfterFilter.get( rowIndex ).getB();

			for ( final ViewId v : a.getViews() )
				IJ.log( "a: " + Group.pvid( v ) );

			for ( final ViewId v : b.getViews() )
				IJ.log( "b: " + Group.pvid( v ) );

			if ( filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ) == null )
			{
				IJ.log( "--- the following pairs exist in filteredResults.getPairwiseResults() #keys=" + filteredResults.getPairwiseResults().keySet().size() );

				int i = 0;
				for ( Pair< Group<ViewId>, Group<ViewId> > pair : filteredResults.getPairwiseResults().keySet() )
				{
					IJ.log( "i=" + i );
					for ( final ViewId v : pair.getA().getViews() )
						IJ.log( "a: " + Group.pvid( v ) );

					for ( final ViewId v : pair.getA().getViews() )
						IJ.log( "b: " + Group.pvid( v ) );
				}

				return null;
			}

			IJ.log( "getPairwiseResults: " + filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ) );
			IJ.log( "pair: " + filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ).pair() );
			IJ.log( "getA: " + filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ).pair().getA() );
			*/
			// THIS IS A HACK, sometimes the pair from activeLinksAfterFilter.get( rowIndex ) does not exist in filteredResults.getPairwiseResults()
			// TODO: figure out why :)
			if ( filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ) == null )
				return "";

			final Group< ViewId > views = filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ).pair().getA();

			return views.toString();
		}
		else if (columnIndex == 1)
		{
			// THIS IS A HACK, sometimes the pair from activeLinksAfterFilter.get( rowIndex ) does not exist in filteredResults.getPairwiseResults()
			// TODO: figure out why :)
			if ( filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ) == null )
				return "";

			final Group< ViewId > views = filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ).pair().getB();
			return views.toString();
		}
		else if (columnIndex == 2)
		{
			// THIS IS A HACK, sometimes the pair from activeLinksAfterFilter.get( rowIndex ) does not exist in filteredResults.getPairwiseResults()
			// TODO: figure out why :)
			if ( filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ) == null )
				return "";

			return filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ).r();
		}
		else if (columnIndex == 3)
		{
			// THIS IS A HACK, sometimes the pair from activeLinksAfterFilter.get( rowIndex ) does not exist in filteredResults.getPairwiseResults()
			// TODO: figure out why :)
			if ( filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ) == null )
				return "";

			double[] shift = filteredResults.getPairwiseResults().get( activeLinksAfterFilter.get( rowIndex ) ).getTransform().getRowPackedCopy();
			
			StringBuilder res = new StringBuilder();
			// round to 3 decimal places
			DecimalFormat df = new DecimalFormat( "#.###" );
			df.setRoundingMode( RoundingMode.HALF_UP );

			// TODO: for now, just display the translation part of the transform
			res.append(df.format( shift[3]) );
			res.append(", ");
			res.append(df.format(shift[7]) );
			res.append(", ");
			res.append(df.format( shift[11]) );
			return res.toString();
		}
		else
			return "";
	}

	@Override
	public void setStitchingResults(StitchingResults res)
	{
		this.results = res;
		this.filteredResults = new FilteredStitchingResults( results, demoLinkOverlay );
	}

	@Override
	public void fireTableDataChanged()
	{
		setActiveLinks( new ArrayList<>(activeLinksBeforeFilter) );
		super.fireTableDataChanged();
	}
	
	
}
