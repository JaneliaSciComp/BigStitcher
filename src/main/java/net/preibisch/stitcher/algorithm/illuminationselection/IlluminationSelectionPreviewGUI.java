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
package net.preibisch.stitcher.algorithm.illuminationselection;


import java.awt.event.WindowAdapter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.CellEditorListener;
import javax.swing.event.ChangeEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;

import bdv.BigDataViewer;
import bdv.SpimSource;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.state.SourceState;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

public class IlluminationSelectionPreviewGUI
{
	private AtomicReference< List< ViewId > > res;
	private CountDownLatch lock;
	
	/**
	 * zip two collections into one List of Pairs [(s_o, t_0), (s_1, t_1), ...]
	 * the size of the result will be the smaller size of the two input Collections 
	 * @param s first
	 * @param t second
	 * @param <S> first item type
	 * @param <T> second item type
	 * @return zipped list of pairs
	 */
	public static <S,T> List<Pair<S,T>> zip(Collection<S> s, Collection<T> t)
	{
		final List<Pair<S,T>> res = new ArrayList<>();
		
		Iterator< S > it1 = s.iterator();
		Iterator< T > it2 = t.iterator();
		
		while(it1.hasNext() && it2.hasNext())
			res.add( new ValuePair<>(it1.next(), it2.next()) );
		
		return res;
	}
	
	public List<ViewId> previewWithGUI(final List< List< BasicViewDescription< ? > > > groupedViews, final List<ViewId> bestViews, final BigDataViewer bdv)
	{
		res = new AtomicReference< List<ViewId> >( null );
		lock = new CountDownLatch( 1 );

		buildGUI( groupedViews, bestViews, bdv);
		
		// wait for result;
		try
		{
			lock.await();
		}
		catch ( InterruptedException e )
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
		
		return res.get();
	}
	
	public static void updateBDV(final BigDataViewer bdv, ViewId bestView)
	{
		// do nothing if BDV is not open
		if (bdv == null)
			return;
		
		// FIXME? is there a better way to get the ViewId of BDV sources?
		List< SourceState< ? > > sources = bdv.getViewer().getState().getSources();
		bdv.getViewer().setTimepoint( bestView.getTimePointId() );
		
		for(int i = 0; i < sources.size(); i++)
		{
			SpimSource< ? > ssource = (SpimSource<?>)((TransformedSource< ? >)sources.get( i ).getSpimSource()).getWrappedSource();
			if (ssource.getSetupId() == bestView.getViewSetupId())
				bdv.getViewer().getVisibilityAndGrouping().setSourceActive( i, true );
			else
				bdv.getViewer().getVisibilityAndGrouping().setSourceActive( i, false );
			
		}
		
		
		
		
	}
	
	public void buildGUI(final List< List< BasicViewDescription< ? > > > groupedViews, final List<ViewId> bestViewsIn, final BigDataViewer bdv)
	{
		new Thread( new Runnable()
		{

			@Override
			public void run()
			{
				
				List<ViewId> bestViews = new ArrayList<>( bestViewsIn );
				
				JFrame frame = new JFrame( "Illumination Selection" );
				frame.addWindowListener( new WindowAdapter()
				{
					public void windowClosing(java.awt.event.WindowEvent e) {
						
						res.set( null );
						lock.countDown();
												
						
					};
				} );
				
				JPanel panel = new JPanel();
				
				@SuppressWarnings("serial")
				JTable table = new JTable( new AbstractTableModel()
				{

					@Override
					public Object getValueAt(int rowIndex, int columnIndex)
					{
						if (columnIndex == 0)
						{
							String res = String.join( ", ",  groupedViews.get( rowIndex ).stream().map( vd -> Integer.toString( vd.getViewSetupId() ) ).collect( Collectors.toList() ));
							return "[" + res + "]";
						}
						else if (columnIndex == 1)
						{
							// get any view description, they should be the same except for illum
							BasicViewDescription< ? > aVD = groupedViews.get( rowIndex ).iterator().next();
							String res = "Angle: " + aVD.getViewSetup().getAttribute( Angle.class ).getName() +
									", Channel: " + aVD.getViewSetup().getAttribute( Channel.class ).getName() +
									", Tile: " + aVD.getViewSetup().getAttribute( Tile.class ).getName() +
									", Timepoint: " + aVD.getTimePoint().getName();
							return res;
						}
						else if (columnIndex == 2)
						{
							int idx = groupedViews.get( rowIndex ).indexOf( bestViews.get( rowIndex ) );
							Illumination ill = ((BasicViewDescription< ? >)groupedViews.get( rowIndex ).get( idx )).getViewSetup().getAttribute( Illumination.class );
							
							return !(ill.getName() .equals( "" )) ? ill.getName() : ill.getId();
						}
						else
							return "";
					}

					@Override
					public String getColumnName(int column)
					{
						switch ( column ) {
						case 0:
							return "ViewIds";
						case 1:
							return "Description";
						case 2:
							return "Illumination";

						default:
							return "";
						}
					}

					@Override
					public int getRowCount()
					{
						// TODO Auto-generated method stub
						return bestViews.size();
					}

					@Override
					public int getColumnCount()
					{
						// TODO Auto-generated method stub
						return 3;
					}
					
					@Override
					public boolean isCellEditable(int rowIndex, int columnIndex) {
						return columnIndex == 2;
					}
				} )
				
				{
					@Override
					public TableCellEditor getCellEditor(int row, int column)
					{
						if (column != 2)
							return super.getCellEditor(row, column);
						else
						{
							List< String > illums = groupedViews.get( row ).stream().map(
									vd -> Integer.toString( vd.getViewSetup().getAttribute( Illumination.class ).getId() ) ).collect( Collectors.toList() );
							
							JComboBox< String > cb = new JComboBox<>(illums.toArray( new String[illums.size()] ));
							DefaultCellEditor editor = new DefaultCellEditor( cb );
							
							editor.addCellEditorListener( new CellEditorListener()
							{								
								@Override
								public void editingStopped(ChangeEvent e)
								{
									BasicViewDescription< ? > bestView = groupedViews.get( row ).get( cb.getSelectedIndex());
									bestViews.set(row, bestView);
									updateBDV( bdv, bestView );
								}
								
								@Override
								public void editingCanceled(ChangeEvent e) {}
							} );
							
							return editor;
						}
					}
					
				};
				
				table.getColumnModel().getColumn( 1 ).setPreferredWidth( 200 );
				
				table.setSelectionMode( ListSelectionModel.SINGLE_SELECTION );
				table.getSelectionModel().addListSelectionListener( e ->  {
					if(!e.getValueIsAdjusting())
						updateBDV( bdv, bestViews.get( table.getSelectedRow() ) );
				});
				
				panel.setLayout( new BoxLayout( panel, BoxLayout.PAGE_AXIS ));
				
				
				panel.add( new JScrollPane( table ) );
				
				JPanel footer = new JPanel();
				footer.setLayout( new BoxLayout( footer, BoxLayout.LINE_AXIS ) );
				
				JButton applyButton = new JButton( "Apply" );
				applyButton.addActionListener( ev -> {
					
					frame.setVisible( false );
					frame.dispose();
					
						res.set( bestViews );
						lock.countDown();
				});
				footer.add( applyButton );
				
				JButton cancelButton = new JButton( "Cancel" );
				cancelButton.addActionListener( ev -> {

					frame.setVisible( false );
					frame.dispose();
					
						res.set( null );
						lock.countDown();
				});
				
				footer.add( cancelButton );
				
				panel.add( footer );
				
				frame.add( panel );
				frame.pack();
				frame.setVisible( true );
				

			}

		} ).start();
	}
}
