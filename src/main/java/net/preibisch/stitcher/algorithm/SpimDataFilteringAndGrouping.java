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


import java.awt.Font;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fiji.util.gui.GenericDialogPlus;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.base.NamedEntity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.datasetmanager.FileListDatasetDefinition;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.SpimDataTools;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.stitcher.algorithm.GroupedViewAggregator.ActionType;
import net.preibisch.stitcher.gui.StitchingExplorerPanel;

public class SpimDataFilteringAndGrouping < AS extends SpimData2 >
{

	public static List<Class<? extends Entity>> entityClasses = new ArrayList<>();
	static 
	{
		entityClasses.add( TimePoint.class );
		entityClasses.add( Channel.class );
		entityClasses.add( Illumination.class );
		entityClasses.add( Angle.class );
		entityClasses.add( Tile.class );
	}

	Set<Class<? extends Entity>> groupingFactors; // attributes by which views are grouped first
	Set<Class<? extends Entity>> axesOfApplication; // axes of application -> we want to process within single instances (e.g. each TimePoint separately)
	Set<Class<? extends Entity>> axesOfComparison; // axes we want to compare
	Map<Class<? extends Entity>, List<? extends Entity>> filters; // filters for the different attributes
	AS data;
	GroupedViewAggregator gva;

	public boolean requestExpertSettingsForGlobalOpt = true;
	boolean dialogWasCancelled = false;

	public SpimDataFilteringAndGrouping(AS data)
	{
		groupingFactors = new HashSet<>();
		axesOfApplication = new HashSet<>();
		axesOfComparison = new HashSet<>();
		filters = new HashMap<>();
		this.data = data;
		gva = new GroupedViewAggregator();
	}

	public AS getSpimData()
	{
		return data;
	}

	public GroupedViewAggregator getGroupedViewAggregator()
	{
		return gva;
	}

	public void addGroupingFactor(Class<? extends Entity> factor ) {
		groupingFactors.add(factor);
	}

	public void addFilter(Class<? extends Entity> cl, List<? extends Entity> instances){
		filters.put(cl, instances);
	}

	public void addFilters(Collection<? extends BasicViewDescription<?>> selected)
	{
		for (Class<? extends Entity> cl : entityClasses)
			filters.put( cl, new ArrayList<>(getInstancesOfAttribute( selected, cl ) ) );
	}

	public void addApplicationAxis(Class<? extends Entity> axis ) {
		axesOfApplication.add(axis);
	}

	public void addComparisonAxis(Class<? extends Entity> axis ) {
		axesOfComparison.add(axis);
	}

	public void clearGroupingFactors()
	{
		groupingFactors.clear();
	}

	public void clearFilters()
	{
		filters.clear();
	}

	public void clearApplicationAxes()
	{
		axesOfApplication.clear();
	}

	public void clearComparisonAxes()
	{
		axesOfComparison.clear();
	}

	public Set< Class< ? extends Entity > > getGroupingFactors()
	{
		return groupingFactors;
	}

	public Set< Class< ? extends Entity > > getAxesOfApplication()
	{
		return axesOfApplication;
	}

	public Set< Class< ? extends Entity > > getAxesOfComparison()
	{
		return axesOfComparison;
	}

	public Map< Class< ? extends Entity >, List< ? extends Entity > > getFilters()
	{
		return filters;
	}

	public List<? extends BasicViewDescription< ? > > getFilteredViews()
	{
		return SpimDataTools.getFilteredViewDescriptions( data.getSequenceDescription(), filters);
	}

	public List< Group< BasicViewDescription< ?  > >> getGroupedViews(boolean filtered)
	{
		final List<BasicViewDescription< ? > > ungroupedElements =
				SpimDataTools.getFilteredViewDescriptions( data.getSequenceDescription(), filtered? filters : new HashMap<>(), false );
		return Group.combineBy( ungroupedElements, groupingFactors);
	}

	public List<Pair<? extends Group< ? extends BasicViewDescription< ? > >, ? extends Group< ? extends BasicViewDescription< ? >>>> getComparisons()
	{
		final List<Pair<? extends Group< ? extends BasicViewDescription< ? > >, ? extends Group< ? extends BasicViewDescription< ? >>>> res = new ArrayList<>();

		// filter first
		final List<BasicViewDescription< ? > > ungroupedElements =
				SpimDataTools.getFilteredViewDescriptions( data.getSequenceDescription(), filters);
		// then group
		final List< Group< BasicViewDescription< ?  > >> groupedElements = 
				Group.combineBy(ungroupedElements, groupingFactors);
		
		// go through possible group pairs
		for (int i = 0; i < groupedElements.size(); ++i)
			for(int j = i+1; j < groupedElements.size(); ++j)
			{
				// we will want to process the pair if:
				// the groups do not differ along an axis along which we want to treat elements individually (e.g. Angle)
				// but they differ along an axis that we want to register (e.g Tile)
				if (!groupsDifferByAny( groupedElements.get( i ), groupedElements.get( j ), axesOfApplication ) 
						&& groupsDifferByAny( groupedElements.get( i ), groupedElements.get( j ), axesOfComparison ))
					res.add(new ValuePair<>(groupedElements.get( i ), groupedElements.get( j )));
			}
		return res;
	}
	
	private static boolean groupsDifferByAny(Iterable< BasicViewDescription< ?  > > vds1, Iterable< BasicViewDescription< ?  > > vds2, Set<Class<? extends Entity>> entities)
	{
		for (Class<? extends Entity> entity : entities)
		{
			for ( BasicViewDescription< ?  > vd1 : vds1)
				for ( BasicViewDescription< ?  > vd2 : vds2)
				{
					if (entity == TimePoint.class)
					{
						if (!vd1.getTimePoint().equals( vd2.getTimePoint() ))
							return true;
					}
					else
					{
						if (!vd1.getViewSetup().getAttribute( entity ).equals( vd2.getViewSetup().getAttribute( entity ) ) )
							return true;
					}
					
				}
		}
		
		return false;
	}


	/**
	 * get all instances of the attribute class cl in the (grouped) views vds. 
	 * @param vds collection of view description lists
	 * @param cl Class of entity to get instances of
	 * @return all instances of cl in the views in vds
	 */
	public static Set<Entity> getInstancesOfAttributeGrouped(Collection< List< BasicViewDescription< ? > > > vds, Class<? extends Entity> cl)
	{
		// make one List out of the nested Collection and getInstancesOfAttribute
		return getInstancesOfAttribute( vds.stream().reduce( new ArrayList<>(), (x, y) -> {x.addAll(y); return x;} ), cl );
	}

	public static Set<Entity> getInstancesOfAttribute(Collection<? extends BasicViewDescription< ? >> vds, Class<? extends Entity> cl)
	{
		Set<Entity> res = new HashSet<>();
		for (BasicViewDescription< ? > vd : vds)
			if (cl == TimePoint.class)
				res.add( vd.getTimePoint() );
			else
				res.add( vd.getViewSetup().getAttribute( cl ) );
		return res;
	}

	/**
	 * get the instances of class cl that are present in at least one ViewDescription of each of the groups in vds.
	 * @param vds collection of view description iterables
	 * @param cl Class of entity to get instances of
	 * @return all instances of cl in the views in vds
	 */
	public static List<? extends Entity> getInstancesInAllGroups(Collection< ? extends Iterable< BasicViewDescription< ? > > > vds, Class<? extends Entity> cl)
	{
		Set<Entity> res = new HashSet<>();
		Iterator< ? extends Iterable< BasicViewDescription< ? > > > it = vds.iterator();
		for (BasicViewDescription< ? > vd : it.next())
			if (cl == TimePoint.class)
				res.add( vd.getTimePoint() );
			else
				res.add( vd.getViewSetup().getAttribute( cl ) );

		while (it.hasNext()) 
		{
			Iterable< BasicViewDescription< ? > > vdli = it.next();
			Set<Entity> resI = new HashSet<>();
			for (BasicViewDescription< ? > vd : vdli)
				if (cl == TimePoint.class)
					resI.add( vd.getTimePoint() );
				else
					resI.add( vd.getViewSetup().getAttribute( cl ) );

			Set<Entity> newRes = new HashSet<>();
			for (Entity e : resI)
			{
				if(res.contains( e ))
					newRes.add( e );
			}
			res = newRes;
		}

		return new ArrayList<>(res);
	}

	public boolean getDialogWasCancelled()
	{
		return dialogWasCancelled;
	}

	// convenience method if we do not know selected views
	public SpimDataFilteringAndGrouping< AS> askUserForFiltering()
	{
		// select all
		return askUserForFiltering( data.getSequenceDescription().getViewDescriptions().values() );
	}

	// convenience method if have a panel (which can give us selected views)
	public SpimDataFilteringAndGrouping< AS > askUserForFiltering(FilteredAndGroupedExplorerPanel< AS > panel)
	{
		List< BasicViewDescription< ? > > views;

		if (panel instanceof GroupedRowWindow)
		{
			Collection< List< BasicViewDescription< ? > > > selectedRowsGroups = ((GroupedRowWindow)panel).selectedRowsGroups();
			views = selectedRowsGroups.stream().reduce( new ArrayList<>(), (x, y) -> {x.addAll(y); return x;} );
		}
		else
			views = panel.selectedRows();
		
		SpimDataFilteringAndGrouping< AS > res = askUserForFiltering( views );
		return res;
		
	}

	public SpimDataFilteringAndGrouping< AS> askUserForFiltering(Collection<? extends BasicViewDescription< ? > > views)
	{

		GenericDialogPlus gdp1 = new GenericDialogPlus( "Select Views To Process" );
		
		final String msg = ( "<html><strong>Select wether you want to process all instances of an attribute <br>"
				+ " or just the currently selected Views</strong> </html>" ) ;
		FileListDatasetDefinition.addMessageAsJLabel(msg, gdp1);
		
		String[] viewSelectionChoices = new String[] {"all", "selected"};
		
		for (Class<? extends Entity> cl : entityClasses)
		{
			boolean allSelected = (getInstancesOfAttribute(views, cl ).containsAll(SpimDataTools.getInstancesOfAttribute( data.getSequenceDescription(), cl )));
			gdp1.addChoice( cl.getSimpleName(), viewSelectionChoices, allSelected ? viewSelectionChoices[0] : viewSelectionChoices[1] );			
		}
		
		gdp1.showDialog();
		if (gdp1.wasCanceled())
		{
			dialogWasCancelled = true;
			return this;
		}
		
		for (Class<? extends Entity> cl : entityClasses)
		{
			boolean useCurrent = gdp1.getNextChoiceIndex() == 1;
			if (useCurrent)
				addFilter( cl, new ArrayList<>(getInstancesOfAttribute(views, cl )) );
		}
		
		return this;
	
	}

	public SpimDataFilteringAndGrouping< AS > askUserForGrouping()
	{
		// use the current filtering as preset
		return askUserForGrouping( SpimDataTools.getFilteredViewDescriptions( data.getSequenceDescription(), getFilters() ), new ArrayList<>(), new HashSet<>() );
	}

	public SpimDataFilteringAndGrouping< AS > askUserForGrouping( FilteredAndGroupedExplorerPanel< AS > panel)
	{
		List< BasicViewDescription< ? > > views;

		if (panel instanceof GroupedRowWindow)
		{
			Collection< List< BasicViewDescription< ? > > > selectedRowsGroups = ((GroupedRowWindow)panel).selectedRowsGroups();
			views = selectedRowsGroups.stream().reduce( new ArrayList<>(), (x, y) -> {x.addAll(y); return x;} );
		}
		else
			views = panel.selectedRows();

		final HashSet< Class<? extends Entity> > comparisonsRequested = new HashSet<>();
		if (StitchingExplorerPanel.class.isInstance( panel ) )
		{
			if (!panel.channelsGrouped())
				comparisonsRequested.add( Channel.class );
			if (!panel.illumsGrouped())
				comparisonsRequested.add( Illumination.class );
			if (!panel.tilesGrouped())
				comparisonsRequested.add( Tile.class );
		}
		return askUserForGrouping( views, panel.getTableModel().getGroupingFactors(), comparisonsRequested);
	}

	public SpimDataFilteringAndGrouping< AS> askUserForGrouping( 
					Collection<? extends BasicViewDescription< ? > > views,
					Collection<Class<? extends Entity>> groupingFactors,
					Collection<Class<? extends Entity>> comparisionFactors)
	{
		GenericDialogPlus gdp2 = new GenericDialogPlus( "Select How to Process Views" );
		
		final String msg = ( "<html><strong>Select how to process the different attributes </strong> <br>"
				+ "<strong>COMPARE:</strong> calculate pairwise shift between instances <br>"
				+ "<strong>GROUP:</strong> combine all instances into one view<br>"
				+ "<strong>TREAT INDIVIDUALLY:</strong> process instances one after the other, but do not compare or group <br> </html>");
		FileListDatasetDefinition.addMessageAsJLabel(msg, gdp2);

		String[] computeChoices = new String[] {"compare", "group", "treat individually"};
		for (Class<? extends Entity> cl : entityClasses)
		{
			boolean isGrouping = groupingFactors.contains( cl );
			boolean isFilterOrSingleton = getFilters().keySet().contains( cl ) || getInstancesOfAttribute(views, cl ).size() <= 1;
			boolean isComparison = comparisionFactors.contains( cl );
			int idx = isGrouping ? 1 : isComparison || !isFilterOrSingleton ? 0 : 2;
			gdp2.addChoice( "How_to_treat_" + cl.getSimpleName() + "s:", computeChoices, computeChoices[idx] );
		}

		gdp2.showDialog();
		if (gdp2.wasCanceled())
		{
			dialogWasCancelled = true;
			return this;
		}

		for (Class<? extends Entity> cl : entityClasses)
		{
			int selection = gdp2.getNextChoiceIndex();
			if (selection == 0)
				addComparisonAxis( cl );
			else if (selection == 1)
				addGroupingFactor( cl );
			else
				addApplicationAxis( cl );
		}

		return this;
	}

	/**
	 * ask user how to aggregate grouped views (for all the entity classes we group by)
	 * if a default choice for a class is provided, the user will not be asked for that class
	 * @param defaultChoices pre-set choices for specific classes (NB: may not be 'pick specific')
	 * @return self
	 */
	public SpimDataFilteringAndGrouping< AS> askUserForGroupingAggregator(final Map<Class<? extends Entity>, ActionType> defaultChoices)
	{
		// ask what to do with grouped views
		GenericDialogPlus gdp = new GenericDialogPlus( "Select How to Treat Grouped Views" );		

//		FileListDatasetDefinition.addMessageAsJLabel("<html><strong>Select which instances of attributes to use in Grouped Views </strong> <br></html>", gdp3);
		gdp.addMessage( "Please specify how to deal with grouped Views.", new Font( Font.SANS_SERIF, Font.BOLD, 14 ), GUIHelper.neutral );

		// filter first
		final List<BasicViewDescription< ? > > ungroupedElements =
						SpimDataTools.getFilteredViewDescriptions( data.getSequenceDescription(), getFilters());
		// then group
		final List< Group< BasicViewDescription< ?  > >> groupedElements = 
						Group.combineBy( ungroupedElements, getGroupingFactors());

		boolean dialogNecessary = false;
		for (Class<? extends Entity> cl : getGroupingFactors())
		{
			if (defaultChoices != null && defaultChoices.containsKey( cl ))
				continue;

			List<String> selection = new ArrayList<>();
			selection.add( "Average " + cl.getSimpleName() +"s" );
//			selection.add(  "pick brightest" );
			List< ? extends Entity > instancesInAllGroups = getInstancesInAllGroups( groupedElements, cl );

			// we only have one instance of entity, do not ask for aggregation in that case
			if (instancesInAllGroups.size() < 2)
				continue;
			// we have more than one instance of any entity -> we have to display dialog
			dialogNecessary = true;

			instancesInAllGroups.forEach( ( e ) -> 
			{
				if (e instanceof NamedEntity)
					selection.add( "use " + cl.getSimpleName() + " " + ((NamedEntity)e).getName());
				else
					selection.add( "use " + cl.getSimpleName() + " " + Integer.toString( e.getId() ));
			});
			
			String[] selectionArray = selection.toArray( new String[selection.size()] );
			gdp.addChoice( cl.getSimpleName() + "s:", selectionArray, selectionArray[0] );
		}

		if (dialogNecessary)
			gdp.showDialog();
			if (gdp.wasCanceled())
			{
				dialogWasCancelled = true;
				return this;
			}

		for (Class<? extends Entity> cl : getGroupingFactors())
		{
			if (defaultChoices != null && defaultChoices.containsKey( cl ))
			{
				getGroupedViewAggregator().addAction(defaultChoices.get( cl ), cl, null);
				continue;
			}

			List< ? extends Entity > instancesInAllGroups = getInstancesInAllGroups( groupedElements, cl );

			// we have only one instance -> "average" (i.e. just keep the one view)
			if (instancesInAllGroups.size() < 2)
			{
				getGroupedViewAggregator().addAction( ActionType.AVERAGE, cl, null );
				continue;
			}

			int nextChoiceIndex = gdp.getNextChoiceIndex();
			if (nextChoiceIndex == 0)
				getGroupedViewAggregator().addAction( ActionType.AVERAGE, cl, null );
//			else if (nextChoiceIndex == 1)
//				getGroupedViewAggregator().addAction( ActionType.PICK_BRIGHTEST, cl, null );
			else
				getGroupedViewAggregator().addAction( ActionType.PICK_SPECIFIC, cl, instancesInAllGroups.get( nextChoiceIndex - 1 ) );
		}
		
		return this;
	}

	public SpimDataFilteringAndGrouping< AS> askUserForGroupingAggregator()
	{
		return askUserForGroupingAggregator( new HashMap<>() );
	}
}
