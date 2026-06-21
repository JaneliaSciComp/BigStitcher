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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.event.TableModelListener;
import javax.swing.table.AbstractTableModel;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ISpimDataTableModel;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.stitcher.algorithm.TransformTools;

public class StitchingTableModelDecorator < AS extends SpimData2 > extends AbstractTableModel implements ISpimDataTableModel<AS>, StitchingResultsSettable{

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	private ISpimDataTableModel<AS> decorated;
	
	StitchingResults res;
	
	static final List< String > columnNames = Arrays.asList( new String [] { "Location", "Avg. r", "# of links" } );
	
	public StitchingTableModelDecorator(ISpimDataTableModel<AS> decorated) {
		this.decorated = decorated;
	}
	
	@Override
	public int getRowCount() {
		return decorated.getRowCount();
	}

	@Override
	public int getColumnCount() {
		// TODO implement for real
		return decorated.getColumnCount() + columnNames.size();
	}

	@Override
	public String getColumnName(int columnIndex) {
		// TODO Auto-generated method stub
		if (columnIndex < decorated.getColumnCount())
			return decorated.getColumnName(columnIndex);
		else
			return columnNames.get( columnIndex - decorated.getColumnCount() );
	}

	@Override
	public Class<?> getColumnClass(int columnIndex) {
		// TODO Auto-generated method stub
		return String.class;
	}

	@Override
	public boolean isCellEditable(int rowIndex, int columnIndex) {
		// TODO Auto-generated method stub
		return false;
	}


	
	@Override
	public Object getValueAt(int rowIndex, int columnIndex)
	{

		// pass on to decorated
		if ( columnIndex < decorated.getColumnCount() )
			return decorated.getValueAt( rowIndex, columnIndex );

		// get location
		else if ( columnIndex - decorated.getColumnCount() == 0 )
		{
			ViewRegistration vr = null;
			final int n = decorated.getElements().get( rowIndex ).size();
			int nPresent = 0;
			boolean allTransformsIdentical = true;
			for ( int vi = 0; vi < n; vi++ )
			{
				final ViewId vid = decorated.getElements().get( rowIndex ).get( vi );
				if ( decorated.getPanel().getSpimData().getSequenceDescription().getMissingViews() != null
						&& decorated.getPanel().getSpimData().getSequenceDescription().getMissingViews()
								.getMissingViews().contains( vid ) )
					continue;

				nPresent++;

				final ViewRegistration vrT = decorated.getPanel().getSpimData().getViewRegistrations()
						.getViewRegistration( vid );
				if ( vr == null )
					vr = vrT;

				allTransformsIdentical &= TransformTools.allAlmostEqual( vr.getModel().getRowPackedCopy(),
						vrT.getModel().getRowPackedCopy(), 1e-5 );

			}

			if ( nPresent == 0 )
				return n + " of " + n + " views missing";
			if ( !allTransformsIdentical )
				return "multiple locations (" + nPresent + " of " + n + " views present)";



			AffineTransform3D tr = vr.getModel(); // getTransformList().get(vr.getTransformList().size()- 1);
			final boolean onlyScaleAndTranslation = TransformTools.isOnlyScaleAndTranslation( tr );

			// apply transform to origin -> location of view origin
			final double[] loc = new double[3];
			tr.apply( loc, loc );

			final StringBuilder res = new StringBuilder();

			if (!onlyScaleAndTranslation)
				res.append( "Affine & " );

			// round to 3 decimal places
			DecimalFormat df = new DecimalFormat( "#.###" );
			df.setRoundingMode( RoundingMode.HALF_UP );

			res.append( df.format( loc[0] ) );
			res.append( ", " );
			res.append( df.format( loc[1] ) );
			res.append( ", " );
			res.append( df.format( loc[2] ) );
			return res.toString();
		}

		// get avg. correlation
		else if ( columnIndex - decorated.getColumnCount() == 1 )
		{
			final Set< ViewId > vid = new HashSet<>( decorated.getElements().get( rowIndex ) );
			SpimData2.filterMissingViews( decorated.getPanel().getSpimData(), vid );

			DecimalFormat df = new DecimalFormat( "#.###" );
			df.setRoundingMode( RoundingMode.HALF_UP );

			return df.format( res.getAvgCorrelation( vid ) );

		}

		// get no. of links
		else if ( columnIndex - decorated.getColumnCount() == 2 )
		{
			final Set< ViewId > vid = new HashSet<>( decorated.getElements().get( rowIndex ) );
			SpimData2.filterMissingViews( decorated.getPanel().getSpimData(), vid );
			return ( res.getAllPairwiseResultsForViewId( vid ).size() );
		}

		// should never be reached
		return null;

	}

	@Override
	public void addTableModelListener(TableModelListener l) {decorated.addTableModelListener(l);}

	@Override
	public void removeTableModelListener(TableModelListener l) {decorated.removeTableModelListener(l);}

	@Override
	public void clearSortingFactors() {decorated.clearSortingFactors();}

	@Override
	public void addSortingFactor(Class<? extends Entity> factor) {decorated.addSortingFactor(factor);}

	@Override
	public void clearGroupingFactors() {decorated.clearGroupingFactors();}

	@Override
	public void addGroupingFactor(Class<? extends Entity> factor) {decorated.addGroupingFactor(factor);}

	@Override
	public void clearFilters() {decorated.clearFilters();}

	@Override
	public void addFilter(Class<? extends Entity> cl, List<? extends Entity> instances) {decorated.addFilter(cl, instances);}

	@Override
	public List<List<BasicViewDescription<?>>> getElements() { return decorated.getElements(); }

	@Override
	public void sortByColumn(int column) {
		if (column < decorated.getColumnCount())
			decorated.sortByColumn(column);		
	}

	@Override
	public ExplorerWindow<AS> getPanel() { return decorated.getPanel(); }

	@Override
	public void setStitchingResults(StitchingResults res)
	{
		this.res = res;		
	}

	@Override
	public int getSpecialColumn(SpecialColumnType type)
	{
		return -1;
	}

	@Override
	public void setColumnClasses(List< Class< ? extends Entity > > columnClasses)
	{
		decorated.setColumnClasses( columnClasses );		
	}

	@Override
	public Set< Class< ? extends Entity > > getGroupingFactors(){return decorated.getGroupingFactors();}

	@Override
	public Map< Class< ? extends Entity >, List< ? extends Entity > > getFilters()
	{
		return decorated.getFilters();
	}

	@Override
	public void updateElements() { decorated.updateElements(); }

}
