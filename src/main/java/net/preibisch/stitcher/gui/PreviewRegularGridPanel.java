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

import java.awt.Image;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;

import bdv.BigDataViewer;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.state.SourceState;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.Angle;
import net.imglib2.Dimensions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.grid.RegularTranformHelpers;
import net.preibisch.mvrecon.fiji.datasetmanager.grid.RegularTranformHelpers.GridPreset;
import net.preibisch.mvrecon.fiji.datasetmanager.grid.RegularTranformHelpers.RegularTranslationParameters;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import util.BDVTools;
import net.preibisch.mvrecon.fiji.spimdata.explorer.SelectedViewDescriptionListener;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class PreviewRegularGridPanel <AS extends SpimData2 > extends JPanel implements SelectedViewDescriptionListener< AS >
{
	public static boolean expertMode = false;

	public static int[] oldTiling = null;
	public static double[] oldOverlap = null;
	public static boolean[] alternatingOld = null;
	public static boolean[] increasingOld = null;
	public static int[] dimensionOrderOld = null;
	public static Boolean keepMetadataRotationOld = true;

	private ExplorerWindow< AS > parent;

	// UI elements
	private List<JCheckBox> alternatingCheckboxes;
	private List<JCheckBox> increasingCheckboxes;
	private List<JSlider> overlapSliders;
	private List<JSpinner> tileCounts;
	private JTextField orderTextField;
	private JLabel orderWarningLabel;
	//private JLabel stepsWarningLabel;
	private JPanel presetPanel;
	private JCheckBox rotationCheckBox;

	// state
	private boolean[] alternating;
	private boolean[] increasing;
	private int[] dimensionOrder;
	private int[] steps;
	private double[] overlaps;
	private boolean rotate = true;
	private List<List<BasicViewDescription< ? >>> selectedVDs;

	private boolean linkedOverlaps = true;
	private boolean zEnabled = false;

	// save old transformation to undo if we cancel
	private AffineTransform3D oldViewerTransform;


	private final static String[] dimensionNames = new String[] {"X", "Y", "Z"};

	public PreviewRegularGridPanel(ExplorerWindow< AS > parent)
	{
		int iconSizeX = 80;
		int iconSizeY = 80;

		Image[][] images = new Image[2][8];
		try
		{
			for (int i = 0; i < 2; i++)
				for (int j = 0; j < 8; j++)
					images[i][j] = ImageIO.read( RegularTranformHelpers.imageFiles.get(i * 8 + j) ).getScaledInstance( iconSizeX, iconSizeY, Image.SCALE_SMOOTH );
		}
		catch (IOException e)
		{
			IOFunctions.printErr( "WARNING: Could not load preset icons" );
		}

		this.parent = parent;

		saveOldTransformAndMoveToOriginIfNecessary();

		this.setLayout( new BoxLayout( this, BoxLayout.PAGE_AXIS ) );


		// preset Icon panel

		presetPanel = new JPanel();
		presetPanel.setLayout( new BoxLayout( presetPanel, BoxLayout.PAGE_AXIS ) );
		
		JPanel presetPanelRow1 = new JPanel();
		presetPanelRow1.setLayout( new BoxLayout( presetPanelRow1, BoxLayout.LINE_AXIS ) );		
		for (int i = 0; i < 8; i++)
		{
			JButton imgI = new JButton( new ImageIcon( images[0][i] ) );
			final Integer ii = i;
			imgI.addActionListener( e -> applyGridPreset( RegularTranformHelpers.presets.get( ii ) ) );
			presetPanelRow1.add( imgI );
		}

		JPanel presetPanelRow2 = new JPanel();
		presetPanelRow2.setLayout( new BoxLayout( presetPanelRow2, BoxLayout.LINE_AXIS ) );		
		for (int i = 0; i < 8; i++)
		{
			JButton imgI = new JButton( new ImageIcon( images[1][i] ) );
			final Integer ii = i;
			imgI.addActionListener( e -> applyGridPreset(  RegularTranformHelpers.presets.get( 8 + ii ) ) );
			presetPanelRow2.add( imgI );
		}

		presetPanel.add( presetPanelRow1 );
		presetPanel.add( presetPanelRow2 );
		this.add( presetPanel );

		final Pair< Integer, Integer > suggestedTiling = RegularTranformHelpers.suggestTiles( ((GroupedRowWindow)parent).selectedRowsViewIdGroups().size() );
		// steps in each dimension
		tileCounts = new ArrayList<>();
		tileCounts.add( new JSpinner( new SpinnerNumberModel( oldTiling == null ? (int) suggestedTiling.getA() : oldTiling[0], 1, Integer.MAX_VALUE, 1 ) ) );
		tileCounts.add( new JSpinner( new SpinnerNumberModel( oldTiling == null ? (int) suggestedTiling.getB() : oldTiling[1], 1, Integer.MAX_VALUE, 1 ) ) );
		tileCounts.add( new JSpinner( new SpinnerNumberModel( oldTiling == null ? 1 : oldTiling[2], 1, Integer.MAX_VALUE, 1 ) ) );

		JPanel tileSpinnerPanel = new JPanel();
		tileSpinnerPanel.setLayout( new BoxLayout( tileSpinnerPanel, BoxLayout.LINE_AXIS ) );
		int i = 0;
		for (JSpinner tileSpinner : tileCounts)
		{
			JLabel dimensionLab = new JLabel( dimensionNames[i++] );
			dimensionLab.setBorder( BorderFactory.createEmptyBorder( 0, 18, 0, 0 ) );
			tileSpinnerPanel.add( dimensionLab );
			tileSpinnerPanel.add( tileSpinner );
			tileSpinner.addChangeListener( (e) -> update()); 
		}
	

		this.add( new JLabel( "Tiles in each dimension" ) );
		this.add( tileSpinnerPanel );

		// sliders for overlap percents
		overlapSliders = new ArrayList<>();
		overlapSliders.add( new JSlider( JSlider.HORIZONTAL, 0, 100, oldOverlap == null ? 10 : (int) Math.round( oldOverlap[0] * 100) ) );
		overlapSliders.add( new JSlider( JSlider.HORIZONTAL, 0, 100, oldOverlap == null ? 10 : (int) Math.round( oldOverlap[1] * 100) ) );
		overlapSliders.add( new JSlider( JSlider.HORIZONTAL, 0, 100, oldOverlap == null ? 10 : (int) Math.round( oldOverlap[2] * 100) ) );
		overlapSliders.get( 2 ).setEnabled( zEnabled );
		
		JPanel overlapSlidersPanel = new JPanel();
		overlapSlidersPanel.setLayout( new BoxLayout( overlapSlidersPanel, BoxLayout.LINE_AXIS ) );
		i = 0;
		for (JSlider c : overlapSliders)
		{
			JLabel dimensionLab = new JLabel( dimensionNames[i++] );
			dimensionLab.setBorder( BorderFactory.createEmptyBorder( 0, 18, 0, 0 ) );
			overlapSlidersPanel.add( dimensionLab );
			overlapSlidersPanel.add( c );
			final JLabel valueDisplay = new JLabel( Integer.toString( c.getValue() ) + " %" );
			overlapSlidersPanel.add( valueDisplay );
			c.addChangeListener( (e) -> {	if (linkedOverlaps)
												for (JSlider olS : overlapSliders)
													olS.setValue(c.getValue());
											update();
											valueDisplay.setText( Integer.toString( c.getValue() ) + " %" );
											} );
		}
		JToggleButton linkButton = new JToggleButton( "link" );
		linkButton.setSelected( true );
		linkButton.addActionListener( e -> linkedOverlaps = linkButton.isSelected() );
		overlapSlidersPanel.add( linkButton );
		
		this.add( new JLabel( "Overlap in dimensions" ) );
		this.add( overlapSlidersPanel );


//		rotationCheckBox = new JCheckBox( "Re-apply Angle rotation from metadata?", keepMetadataRotationOld == null ? true : keepMetadataRotationOld );
//		rotationCheckBox.addActionListener( (e) -> update());
//		this.add( rotationCheckBox );

		// checkboxes to set wether the grid alternates along a given axis
		alternatingCheckboxes = new ArrayList<>();
		alternatingCheckboxes.add( new JCheckBox( "X", alternatingOld == null ? true : alternatingOld[0] ) );
		alternatingCheckboxes.add( new JCheckBox( "Y", alternatingOld == null ? true : alternatingOld[1] ) );
		alternatingCheckboxes.add( new JCheckBox( "Z", alternatingOld == null ? true : alternatingOld[2] ) );
		alternatingCheckboxes.get( 2 ).setEnabled( zEnabled );

		JPanel alternatingCheckboxesPanel = new JPanel();
		alternatingCheckboxesPanel.setLayout( new BoxLayout( alternatingCheckboxesPanel, BoxLayout.LINE_AXIS ) );

		for ( JCheckBox c : alternatingCheckboxes )
		{
			alternatingCheckboxesPanel.add( c );
			c.addActionListener( (e) -> update() );
		}

		if (expertMode)
		{
			this.add( new JLabel( "Alternating in dimensions" ) );
			this.add( alternatingCheckboxesPanel );
		}

		// checkboxes to set wether the coordinates increase along a given axis
		increasingCheckboxes = new ArrayList<>();
		increasingCheckboxes.add( new JCheckBox( "X", increasingOld == null ? true : increasingOld[0] ) );
		increasingCheckboxes.add( new JCheckBox( "Y", increasingOld == null ? true : increasingOld[1] ) );
		increasingCheckboxes.add( new JCheckBox( "Z", increasingOld == null ? true : increasingOld[2] ) );
		increasingCheckboxes.get( 2 ).setEnabled( zEnabled );

		JPanel increasingCheckboxesPanel = new JPanel();
		increasingCheckboxesPanel.setLayout( new BoxLayout( increasingCheckboxesPanel, BoxLayout.LINE_AXIS ) );

		for ( JCheckBox c : increasingCheckboxes )
		{
			increasingCheckboxesPanel.add( c );
			c.addActionListener( (e) -> update() );
		}

		if (expertMode)
		{
			this.add( new JLabel( "Increasing in dimensions" ) );
			this.add( increasingCheckboxesPanel );
		}

		// dimension order
		orderTextField = new JTextField( dimensionOrderOld == null ? "x, y" : 
			String.join( ",", dimensionNames[dimensionOrderOld[0]], dimensionNames[dimensionOrderOld[1]], dimensionNames[dimensionOrderOld[2]] ), 30 );
		orderWarningLabel = new JLabel("");
		orderTextField.getDocument().addDocumentListener( new LinkExplorerPanel.SimpleDocumentListener( ev -> update() ) ); 

		if (expertMode)
		{
			this.add( new JLabel( "Dimension order" ) );
			this.add( orderTextField );
			this.add( orderWarningLabel );
		}


		JPanel buttonPanel = new JPanel();
		buttonPanel.setLayout( new BoxLayout( buttonPanel, BoxLayout.LINE_AXIS ) );
		
		JButton cancelButton = new JButton( "cancel" );
		cancelButton.addActionListener( ( e ) -> {((JFrame)this.getTopLevelAncestor()).dispose(); quit();}) ;
		
		JButton applyButton = new JButton( "Apply Transformations" );
		applyButton.addActionListener( ( e ) -> {applyButtonClicked(); }) ; 
		buttonPanel.add( cancelButton );
		buttonPanel.add( applyButton );

		JPanel warningPanel = new JPanel();
		warningPanel.setLayout( new BoxLayout( warningPanel, BoxLayout.LINE_AXIS ) );
		String message1 = "<html><strong>WARNING:</strong> Applying will overwrite all tranformations but the calibration with the new translations</html>";
		final JLabel warningLabel = new JLabel( message1 );
		warningLabel.setBorder( BorderFactory.createEmptyBorder( 3, 18, 0, 0 ) );
		warningPanel.add( warningLabel );

		this.add( warningPanel );
		this.add( buttonPanel );

		// add this as a listener for selection changes in parent view explorer
		// this should trigger update immediately
		FilteredAndGroupedExplorerPanel< AS > FilteredAndGroupedExplorerPanel = (FilteredAndGroupedExplorerPanel< AS >)parent;
		FilteredAndGroupedExplorerPanel.addListener( this );
		
		if (oldOverlap == null)
		{
			((JButton)presetPanelRow1.getComponent( 0 )).doClick();
		}
	}

	private void applyGridPreset( GridPreset gp)
	{
		for (int i = 0; i < gp.alternating.length; i ++)
			alternatingCheckboxes.get( i ).setSelected( gp.alternating[i] );

		for (int i = 0; i < gp.increasing.length; i ++)
			increasingCheckboxes.get( i ).setSelected( gp.increasing[i] );

		orderTextField.setText( gp.dimensionOrder );
		update();
	}

	/*
	 * save old BDV View Transformation if we haven't done that before
	 * move to origin, keep zoom level
	 */
	private void saveOldTransformAndMoveToOriginIfNecessary()
	{
		if (oldViewerTransform == null)
		{
			if (parent.bdvPopup() == null)
				return;

			BigDataViewer bdv = parent.bdvPopup().getBDV();

			if (bdv == null)
				return;

			oldViewerTransform = new AffineTransform3D();
			bdv.getViewer().getState().getViewerTransform( oldViewerTransform );

			AffineTransform3D t = oldViewerTransform.copy();
			t.set( 0.0, 0, 3 );
			t.set( 0.0, 1, 3 );
			t.set( 0.0, 2, 3 );
			bdv.getViewer().setCurrentViewerTransform( t );

			bdv.getViewer().requestRepaint();
		}
	}

	public void update()
	{
//		rotate = rotationCheckBox.isSelected();

		// update state from gui
		alternating = new boolean[3];
		alternating[0] = alternatingCheckboxes.get( 0 ).isSelected();
		alternating[1] = alternatingCheckboxes.get( 1 ).isSelected();
		alternating[2] = alternatingCheckboxes.get( 2 ).isSelected();
		
		increasing = new boolean[3];
		increasing[0] = increasingCheckboxes.get( 0 ).isSelected();
		increasing[1] = increasingCheckboxes.get( 1 ).isSelected();
		increasing[2] = increasingCheckboxes.get( 2 ).isSelected();
		
		overlaps = new double[3];
		overlaps[0] = (double) overlapSliders.get( 0 ).getValue() / 100.0;
		overlaps[1] = (double) overlapSliders.get( 1 ).getValue() / 100.0;
		overlaps[2] = (double) overlapSliders.get( 2 ).getValue() / 100.0;
		
		dimensionOrder = RegularTranformHelpers.getDimensionOrder( orderTextField.getText() );
		if (dimensionOrder == null)
			orderWarningLabel.setText( "<html><p style=\"color:red \"> WARNING: dimension order must be two or three of x,y or z separated by commas </p></html>" );
		else
			orderWarningLabel.setText("");

		steps = new int[3];
		steps[0] = (int) tileCounts.get( 0 ).getValue();
		steps[1] = (int) tileCounts.get( 1 ).getValue();
		steps[2] = (int) tileCounts.get( 2 ).getValue();
		
		if ((dimensionOrder == null) || (steps == null))
			return;
		
		// enable z settings if we have more than 1 tile in z
		zEnabled = steps[2] != 1;		
		overlapSliders.get( 2 ).setEnabled( zEnabled );
		alternatingCheckboxes.get( 2 ).setEnabled( zEnabled );
		increasingCheckboxes.get( 2 ).setEnabled( zEnabled );

		updateBDV();
		
	}
	
	
	private void updateBDV()
	{

		BigDataViewer bdv = parent.bdvPopup().getBDV();
		if ( bdv != null )

		{
			
			//BDVTools.resetBDVManualTransformations( bdv );

			RegularTranslationParameters params = new RegularTranslationParameters();
			params.nDimensions = 3;
			params.alternating = alternating;
			params.dimensionOrder = dimensionOrder;
			params.increasing = increasing;
			params.overlaps = overlaps;
			params.nSteps = steps;
			params.keepRotation = rotate;

			Dimensions size = parent.getSpimData().getSequenceDescription().getViewDescriptions()
					.get( selectedVDs.get( 0 ).get( 0 ) ).getViewSetup().getSize();
			List< Translation3D > generateRegularGrid = RegularTranformHelpers.generateRegularGrid( params, size );
			int i = 0;
			for ( List< BasicViewDescription< ? > > lvd : selectedVDs )
			{

				// we did not generate enough transformations
				// -> leave the rest of the views as-is
				if (i>generateRegularGrid.size())
					break;

				for ( BasicViewDescription< ? > vd : lvd )
				{
					
					int sourceIdx = StitchingExplorerPanel.getBDVSourceIndex( vd.getViewSetup(), parent.getSpimData() );
					SourceState< ? > s = parent.bdvPopup().getBDV().getViewer().getState().getSources().get( sourceIdx );
					

					ViewRegistration vr = parent.getSpimData().getViewRegistrations().getViewRegistration( vd );
					AffineTransform3D inv = vr.getModel().copy().inverse();
					AffineTransform3D calib = new AffineTransform3D();
					calib.set( vr.getTransformList().get( vr.getTransformList().size() - 1 ).asAffine3D().getRowPackedCopy() );

					//invAndCalib.preConcatenate( vr.getTransformList().get( 0 ).asAffine3D() );

					AffineTransform3D grid = new AffineTransform3D();
					if (i < generateRegularGrid.size())
						grid.set( generateRegularGrid.get( i ).getRowPackedCopy() );

					AffineTransform3D gridTransform = ( i < generateRegularGrid.size() )
							? inv.preConcatenate( grid.copy() ) : inv.copy();

					gridTransform.preConcatenate( calib );

					if (rotate)
					{
						AffineTransform3D rotation = new AffineTransform3D();
						Pair< Double, Integer > rotAngleAndAxis = RegularTranformHelpers.getRoatationFromMetadata( vd.getViewSetup().getAttribute( Angle.class ) );
						if (rotAngleAndAxis != null)
						{
							rotation.rotate( rotAngleAndAxis.getB(), rotAngleAndAxis.getA() );
							gridTransform.preConcatenate( rotation.copy() );
						}
					}

					( (TransformedSource< ? >) s.getSpimSource() ).setFixedTransform( gridTransform );

				}
				i++;
			}

			
			bdv.getViewer().requestRepaint();

		}

	}

	private void applyButtonClicked()
	{
		String message2 = "<html>apply to all TimePoints?</html>";

		final boolean onlyOneTP = parent.getSpimData().getSequenceDescription().getTimePoints().getTimePointsOrdered().size() <= 1;
		boolean allTPs = false;
		if (!onlyOneTP)
		{
			int confirm2 = JOptionPane.showConfirmDialog( this, message2, "Apply to dataset", JOptionPane.YES_NO_CANCEL_OPTION );
			if (confirm2 == JOptionPane.CANCEL_OPTION)
				return;
			allTPs = confirm2 == JOptionPane.YES_OPTION;
		}

		RegularTranslationParameters params = new RegularTranslationParameters();
		params.nDimensions = 3;
		params.alternating = alternating;
		params.dimensionOrder = dimensionOrder;
		params.increasing = increasing;
		params.overlaps = overlaps;
		params.nSteps = steps;
		params.keepRotation = rotate;

		alternatingOld = alternating.clone();
		dimensionOrderOld = dimensionOrder.clone();
		increasingOld = increasing.clone();
		oldOverlap = overlaps.clone();
		oldTiling = steps.clone();
		keepMetadataRotationOld = rotate;

		List< Group< BasicViewDescription< ? > > > selectedVdsGroup = selectedVDs.stream().map( l -> Group.toGroup( l ).get( 0 ) ).collect( Collectors.toList() );
		RegularTranformHelpers.applyToSpimData( parent.getSpimData() , selectedVdsGroup, params, allTPs );

		// reset viewer transform to recall to current transform & update with new sources
		if (parent.bdvPopup().bdvRunning())
		{
			parent.bdvPopup().getBDV().getViewer().getState().getViewerTransform( oldViewerTransform );
			parent.bdvPopup().updateBDV();
		}

		// close the window
		((JFrame)this.getTopLevelAncestor()).dispose();
		quit();
	}

	

	public void quit()
	{
		FilteredAndGroupedExplorerPanel< AS > panel = (FilteredAndGroupedExplorerPanel< AS >)parent;
		panel.getListeners().remove( this );
		
		final BigDataViewer bdv = parent.bdvPopup().getBDV();		
		if(!(bdv == null))
		{
			bdv.getViewer().setCurrentViewerTransform( oldViewerTransform );
		
			BDVTools.resetBDVManualTransformations( parent.bdvPopup().getBDV() );
			parent.bdvPopup().getBDV().getViewer().requestRepaint();
		}

		// in case the parent is waiting on completion: notify
		synchronized ( parent )
		{
			parent.notifyAll();
		}
	}

	@Override
	public void selectedViewDescriptions( List< List< BasicViewDescription< ? > > > viewDescriptions )
	{
		//System.out.println( " selection upd " );
		selectedVDs = viewDescriptions;
		
		// sort the selected groups by the view id of the first member
		Collections.sort( selectedVDs, (x, y) -> x.get( 0 ).compareTo( y.get( 0 ) ) );
		
		// hacky solution, wait a bit with update since parent will reset BDV after selection change
		new Timer().schedule(new TimerTask()
		{

			public void run()
			{
				update();
				
			}
		}, 100 );

	}

	@Override
	public void updateContent(AS data)
	{
		// TODO Auto-generated method stub
		
	}

	@Override
	public void save()
	{
		// TODO Auto-generated method stub
		
	}

	/*
	 * get int array from 2 or 3 comma-separated numbers, return null if fragments cannot be parsed as int or there are != 2|3 numbers
	 */
	private static int[] getSteps(String s)
	{
		List<String> splitted = Arrays.asList( s.split( "," ) );

		for (int i = 0; i < splitted.size(); ++i)
			splitted.set( i, splitted.get( i ).replaceAll( "\\s+", "" ));

		List<Integer> steps;
		try
		{
			steps = splitted.stream().map( ( st ) -> Integer.parseInt( st ) ).collect( Collectors.toList() );
		}
		catch (NumberFormatException e)
		{
			return null;
		}

		if ((steps.size() < 2) || (steps.size() > 3))
			return null;

		int[] res = new int[3];
		res[0] = steps.get( 0 );
		res[1] = steps.get( 1 );
		res[2] = steps.size() == 3 ? steps.get( 2 ) : 1;

		return res;
	}


	
	
	public static void main(String[] args)
	{
		for (int i = 1; i<50; i++)
		{
			Pair< Integer, Integer > tiling = RegularTranformHelpers.suggestTiles( i );
			System.out.println( tiling.getA() + "," + tiling.getB() );
		}
	}
}
