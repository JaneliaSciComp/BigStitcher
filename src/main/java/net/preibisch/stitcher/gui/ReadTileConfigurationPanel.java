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
package net.preibisch.stitcher.gui;

import java.awt.Dimension;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import util.BDVTools;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;

public class ReadTileConfigurationPanel extends JPanel
{

	private static final long serialVersionUID = 1L;
	private AbstractSpimData< ? > data;
	private BasicBDVPopup bdvPopup;
	private JFrame parent;
	private JTextField fileTextField;
	private JTable table;
	private JCheckBox pixelUnitCB;
	private JCheckBox keepRotationCB;
	private List<Pair<ViewId, Translation3D>> previewMap;

	public ReadTileConfigurationPanel(AbstractSpimData< ? > data, BasicBDVPopup bdvPopup, JFrame parent)
	{
		this.data = data;
		this.bdvPopup = bdvPopup;
		this.parent = parent;
		this.previewMap = new ArrayList<>();
		initGUI();
	}

	private void initGUI()
	{
		this.setLayout( new BoxLayout( this, BoxLayout.PAGE_AXIS ) );
		this.setBorder( BorderFactory.createEmptyBorder( 10, 20, 10, 20 ) );
		final JPanel filePanel = new JPanel();

		fileTextField = new JTextField( 35 );
		JButton openButton = new JButton( "Browse..." );
		filePanel.setLayout( new BoxLayout( filePanel, BoxLayout.LINE_AXIS ) );
		filePanel.add( new JLabel( "Tile configuration file: " ) );
		filePanel.add( fileTextField );
		filePanel.add( openButton );
		this.add( filePanel );

		table = new JTable( new AbstractTableModel()
		{
			private static final long serialVersionUID = 1L;
			@Override
			public String getColumnName(int column)
			{
				return new String[]{"View Setup", "TimePoint", "Location"}[column];
			}

			@Override
			public Object getValueAt(int rowIndex, int columnIndex)
			{
				if (columnIndex == 0)
					return Integer.toString( previewMap.get( rowIndex ).getA().getViewSetupId() );
				else if (columnIndex == 1)
					return Integer.toString( previewMap.get( rowIndex ).getA().getTimePointId() );
				else
				{
					final StringBuilder sb = new StringBuilder();
					final AffineGet tr = previewMap.get( rowIndex ).getB();
					// locations : round to 3 decimal places
					DecimalFormat df = new DecimalFormat( "#.###" );
					df.setRoundingMode( RoundingMode.HALF_UP );
					sb.append( df.format( tr.get( 0, 3 ) ) );
					sb.append( ", " );
					sb.append( df.format( tr.get( 1, 3 ) ) );
					sb.append( ", " );
					sb.append( df.format( tr.get( 2, 3 ) ) );
					return sb.toString();
				}
			}

			@Override
			public int getRowCount()
			{
				return previewMap.size();
			}

			@Override
			public int getColumnCount()
			{
				return 3;
			}
		} );

		final DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
		centerRenderer.setHorizontalAlignment( JLabel.CENTER );

		// center all columns
		for ( int column = 0; column < table.getModel().getColumnCount(); ++column ){
			table.getColumnModel().getColumn( column ).setCellRenderer( centerRenderer );
		}

		table.setPreferredScrollableViewportSize( new Dimension( 400, 300 ) );
		this.add( new JScrollPane( table ) );

		// handle open file
		openButton.addActionListener( ev -> {
			final JFileChooser fc = new JFileChooser();
			final int ret = fc.showOpenDialog( ReadTileConfigurationPanel.this );

			if (ret == JFileChooser.APPROVE_OPTION)
			{
				final File tcFile = fc.getSelectedFile();
				fileTextField.setText( tcFile.getAbsolutePath() );
			}
		});

		// update on changed path
		fileTextField.getDocument().addDocumentListener( new LinkExplorerPanel.SimpleDocumentListener( ev -> update() ) );

		fileTextField.setDropTarget( new DropTarget()
		{
			private static final long serialVersionUID = 1L;
			public synchronized void drop(DropTargetDropEvent evt)
			{
				try
				{
					evt.acceptDrop( DnDConstants.ACTION_COPY );
					@SuppressWarnings("unchecked")
					List< File > droppedFiles = (List< File >) evt.getTransferable()
							.getTransferData( DataFlavor.javaFileListFlavor );
					// only use first file
					if ( droppedFiles.size() < 1 )
						return;
					fileTextField.setText( droppedFiles.iterator().next().getAbsolutePath() );
				}
				catch ( Exception ex )
				{
					ex.printStackTrace();
				}
			}
		} );

		// checkboxes for use pixel units / keep rotation
		pixelUnitCB = new JCheckBox( "pixel units", true );
		this.add( pixelUnitCB );
		pixelUnitCB.addActionListener( ev -> update() );

		keepRotationCB = new JCheckBox( "keep rotation from metadata", true );
		this.add( keepRotationCB );
		keepRotationCB.addActionListener( ev -> update() );

		final JPanel footer = new JPanel();
		footer.setLayout( new BoxLayout( footer, BoxLayout.LINE_AXIS ) );
		footer.setBorder( BorderFactory.createEmptyBorder( 10, 0, 0, 0 ) );

		final JButton applyButton = new JButton( "Apply" );
		final JButton closeButton = new JButton( "Close" );

		applyButton.addActionListener( event -> apply() );
		closeButton.addActionListener( event -> {
			parent.dispatchEvent(new WindowEvent(parent, WindowEvent.WINDOW_CLOSING));
		} );

		footer.add( closeButton );
		footer.add( applyButton );

		this.add( footer );
	}

	public void update()
	{
		// reset and repaint Bdv if necessary
		if ( bdvPopup.bdvRunning() )
		{
			BDVTools.resetBDVManualTransformations( bdvPopup.getBDV() );
			bdvPopup.getBDV().getViewer().requestRepaint();
		}

		// clear the stored transformation map
		previewMap.clear();

		// get new path
		String path = fileTextField.getText();
		File f = new File(path);
		if (!f.exists())
			return;

		// try to parse
		Map< ViewId, Translation3D > tc = TileConfigurationHelpers.parseTileConfiguration( f );
		if (tc == null)
		{
			previewMap.clear();
			return;
		}

		// set stored transformations (sorted by view id)
		Map< ViewId, Translation3D > transformsForData = TileConfigurationHelpers.getTransformsForData( tc, pixelUnitCB.isSelected(), data );
		transformsForData.forEach( (vid, tr) -> {
			previewMap.add( new ValuePair<>(vid, tr) );
		});
		Collections.sort( previewMap, new Comparator< Pair<ViewId, Translation3D>>()
		{
			@Override
			public int compare(Pair< ViewId, Translation3D > o1, Pair< ViewId, Translation3D > o2)
			{
				return o1.getA().compareTo( o2.getA() );
			}
		} );

		// update bdv and table
		((AbstractTableModel)table.getModel()).fireTableDataChanged();
		if ( bdvPopup.bdvRunning() )
			TileConfigurationHelpers.updateBDVPreview( tc, pixelUnitCB.isSelected(), keepRotationCB.isSelected(), data, bdvPopup.getBDV() );

	}

	public void apply()
	{
		// get the tc file, close without doing anything if parsing fails
		String path = fileTextField.getText();
		File f = new File(path);
		if (!f.exists())
		{
			parent.dispatchEvent(new WindowEvent(parent, WindowEvent.WINDOW_CLOSING));
			return;
		}
		Map< ViewId, Translation3D > tc = TileConfigurationHelpers.parseTileConfiguration( f );
		if (tc == null)
		{
			parent.dispatchEvent(new WindowEvent(parent, WindowEvent.WINDOW_CLOSING));
			return;
		}

		// actually apply
		TileConfigurationHelpers.applyToData( tc, pixelUnitCB.isSelected(), keepRotationCB.isSelected(), data );

		// update bdv
		if (bdvPopup.bdvRunning())
			bdvPopup.updateBDV();

		// close frame
		parent.dispatchEvent(new WindowEvent(parent, WindowEvent.WINDOW_CLOSING));
	}

	public void quit()
	{
		// reset and repaint Bdv if necessary
		if ( bdvPopup.bdvRunning() )
		{
			BDVTools.resetBDVManualTransformations( bdvPopup.getBDV() );
			bdvPopup.getBDV().getViewer().requestRepaint();
		}
	}

}
