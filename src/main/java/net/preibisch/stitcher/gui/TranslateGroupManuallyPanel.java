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
import java.awt.Font;
import java.awt.event.WindowEvent;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.event.ChangeListener;

import com.google.common.base.Strings;

import bdv.SpimSource;
import bdv.tools.transformation.TransformedSource;
import bdv.viewer.Source;
import bdv.viewer.state.SourceState;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.SelectedViewDescriptionListener;
import util.BDVTools;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.stitcher.algorithm.TransformTools;

public class TranslateGroupManuallyPanel extends JPanel implements SelectedViewDescriptionListener< AbstractSpimData< ? > >
{

	private static final long serialVersionUID = 1L;
	private final static AffineTransform3D identity = new AffineTransform3D();
	private final static int conversionFactor = 10;
	private final static double eps = 0.001;
	private final static int VALUE_TEXT_LEN = 6;

	private final Set< ViewId > selected;
	private SpimData2 data;	
	private AffineTransform3D theTransform;
	private Interval bb;
	private BasicBDVPopup bdvPopup;

	private JFrame parent;
	private JSlider xslider;
	private JSlider yslider;
	private JSlider zslider;

	private JLabel xValueTextField;
	private JLabel yValueTextField;
	private JLabel zValueTextField;

	public TranslateGroupManuallyPanel(SpimData2 data, Collection< ? extends ViewId > selectedInitial, BasicBDVPopup bdvPopup, JFrame parent)
	{
		this.parent = parent;
		this.data = data;
		this.bdvPopup = bdvPopup;
		theTransform = new AffineTransform3D();

		selected = new HashSet<>();
		selected.addAll( selectedInitial );

		bb = new BoundingBoxMaximal( selected, data ).estimate( "" );
		
		initGUI();
	}
	
	private void initGUI()
	{
		this.setLayout( new BoxLayout( this, BoxLayout.PAGE_AXIS ) );
		this.setBorder( BorderFactory.createEmptyBorder( 10, 20, 10, 20 ) );
		
		if (bb != null)
		{
			xslider = new JSlider( - (int) bb.dimension( 0 ) * conversionFactor,  (int) bb.dimension( 0 ) * conversionFactor, 0 );
			yslider = new JSlider( - (int) bb.dimension( 1 ) * conversionFactor,  (int) bb.dimension( 1 ) * conversionFactor, 1 );
			zslider = new JSlider( - (int) bb.dimension( 2 ) * conversionFactor,  (int) bb.dimension( 2 ) * conversionFactor, 2 );
		}
		else
		{
			xslider = new JSlider(-1, 1, 0);
			yslider = new JSlider(-1, 1, 0);
			zslider = new JSlider(-1, 1, 0);
			xslider.setEnabled( false );
			yslider.setEnabled( false );
			zslider.setEnabled( false );
		}
		
		xslider.setPreferredSize( new Dimension( 300, 20 ) );
		yslider.setPreferredSize( new Dimension( 300, 20 ) );
		zslider.setPreferredSize( new Dimension( 300, 20 ) );
		
		xslider.addChangeListener( event -> slidersUpdated());
		yslider.addChangeListener( event -> slidersUpdated());
		zslider.addChangeListener( event -> slidersUpdated());
		
		xValueTextField = new JLabel( padLeft( "0.0", VALUE_TEXT_LEN ) );
		yValueTextField = new JLabel( padLeft( "0.0", VALUE_TEXT_LEN ) );
		zValueTextField = new JLabel( padLeft( "0.0", VALUE_TEXT_LEN ) );
		xValueTextField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		yValueTextField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		zValueTextField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		
		final JPanel xSliderPanel = new JPanel();
		xSliderPanel.setLayout( new BoxLayout( xSliderPanel, BoxLayout.LINE_AXIS ) );
		xSliderPanel.add( new JLabel( "X: " ) );
		xSliderPanel.add( xslider );
		xSliderPanel.add( xValueTextField );
		
		final JPanel ySliderPanel = new JPanel();
		ySliderPanel.setLayout( new BoxLayout( ySliderPanel, BoxLayout.LINE_AXIS ) );
		ySliderPanel.add( new JLabel( "Y: " ) );
		ySliderPanel.add( yslider );
		ySliderPanel.add( yValueTextField );
		
		final JPanel zSliderPanel = new JPanel();
		zSliderPanel.setLayout( new BoxLayout( zSliderPanel, BoxLayout.LINE_AXIS ) );
		zSliderPanel.add( new JLabel( "Z: " ) );
		zSliderPanel.add( zslider );
		zSliderPanel.add( zValueTextField );
		
		this.add( xSliderPanel );
		this.add( ySliderPanel );
		this.add( zSliderPanel );
		
		
		final JPanel footer = new JPanel();
		footer.setLayout( new BoxLayout( footer, BoxLayout.LINE_AXIS ) );
		footer.setBorder( BorderFactory.createEmptyBorder( 10, 0, 0, 0 ) );

		final JButton applyButton = new JButton( "Apply" );
		final JButton closeButton = new JButton( "Close" );
		
		applyButton.addActionListener( event -> applyTranslation() );
		closeButton.addActionListener( event -> quit() );
		
		footer.add( closeButton );
		footer.add( applyButton );
		
		this.add( footer );
	}
	
	private void resetSliders()
	{
		// temporarily remove change listeners
		final ChangeListener[] changeListenersX = xslider.getChangeListeners();
		final ChangeListener[] changeListenersY = yslider.getChangeListeners();
		final ChangeListener[] changeListenersZ = zslider.getChangeListeners();

		for ( ChangeListener cl : changeListenersX )
			xslider.removeChangeListener( cl );
		for ( ChangeListener cl : changeListenersY )
			yslider.removeChangeListener( cl );
		for ( ChangeListener cl : changeListenersZ )
			zslider.removeChangeListener( cl );

		if (bb != null)
		{
			xslider.setMinimum( - (int) bb.dimension( 0 ) * conversionFactor );
			xslider.setMaximum(  (int) bb.dimension( 0 ) * conversionFactor );
			xslider.setValue( 0 );
			yslider.setMinimum( - (int) bb.dimension( 1 ) * conversionFactor );
			yslider.setMaximum( (int) bb.dimension( 1 ) * conversionFactor );
			yslider.setValue( 0 );
			zslider.setMinimum( - (int) bb.dimension( 2 ) * conversionFactor );
			zslider.setMaximum(  (int) bb.dimension( 2 ) * conversionFactor );
			zslider.setValue( 0 );
			xslider.setEnabled( true );
			yslider.setEnabled( true );
			zslider.setEnabled( true );
		}
		else
		{
			xslider.setMinimum( -1 );
			xslider.setMaximum( 1 );
			xslider.setValue( 0 );
			yslider.setMinimum( -1 );
			yslider.setMaximum( 1 );
			yslider.setValue( 0 );
			zslider.setMinimum( -1 );
			zslider.setMaximum( 1 );
			zslider.setValue( 0 );
			xslider.setEnabled( false );
			yslider.setEnabled( false );
			zslider.setEnabled( false );
		}

		xValueTextField.setText( padLeft( "0.0", VALUE_TEXT_LEN ) );
		yValueTextField.setText( padLeft( "0.0", VALUE_TEXT_LEN ) );
		zValueTextField.setText( padLeft( "0.0", VALUE_TEXT_LEN ) );
		
		// add listeners again
		for ( ChangeListener cl : changeListenersX )
			xslider.addChangeListener( cl );
		for ( ChangeListener cl : changeListenersY )
			yslider.addChangeListener( cl );
		for ( ChangeListener cl : changeListenersZ )
			zslider.addChangeListener( cl );
	}
	
	public static String padLeft(String in, int minLength)
	{
		return Strings.repeat( " ", (int) Math.max( minLength - in.length(), 0 ) ) + in;
	}
	
	private void applyTranslation()
	{
		// ask user once more
		int userConfirm = JOptionPane.showConfirmDialog( this, "Apply manual transformation to data?", "Confirm", JOptionPane.OK_CANCEL_OPTION );
		if (userConfirm == JOptionPane.CANCEL_OPTION)
			return;
		
		if (data == null)
		{
			resetSliders();
			return;
		}
		
		// update SpimData
		for (ViewId vid : selected)
		{
			final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( vid );
			vr.preconcatenateTransform( new ViewTransformAffine( "Manual Translation", theTransform.copy() ) );
			vr.updateModel();
		}
		
		// reset sliders/transform (so we can apply again)
		theTransform = new AffineTransform3D();
		resetSliders();

		// reset and repaint Bdv if necessary
		if (bdvPopup.bdvRunning())
		{
			BDVTools.resetBDVManualTransformations( bdvPopup.getBDV() );
			bdvPopup.updateBDV();
		}
	}
	
	
	private void slidersUpdated()
	{
		theTransform.set( xslider.getValue() / (double) conversionFactor, 0, 3 );
		theTransform.set( yslider.getValue() / (double) conversionFactor, 1, 3 );
		theTransform.set( zslider.getValue() / (double) conversionFactor, 2, 3 );

		xValueTextField.setText( padLeft( Double.toString( xslider.getValue() / (double) conversionFactor ), VALUE_TEXT_LEN ) );
		yValueTextField.setText( padLeft( Double.toString( yslider.getValue() / (double) conversionFactor ), VALUE_TEXT_LEN ) );
		zValueTextField.setText( padLeft( Double.toString( zslider.getValue() / (double) conversionFactor ), VALUE_TEXT_LEN ) );
		
		if (!bdvPopup.bdvRunning())
			return;

		BDVTools.resetBDVManualTransformations( bdvPopup.getBDV() );
		
		final List< SourceState< ? > > sourceStates = bdvPopup.getBDV().getViewer().getVisibilityAndGrouping().getSources();
		final int currentTimepoint = bdvPopup.getBDV().getViewer().getState().getCurrentTimepoint();
		
		for (final SourceState< ? > state : sourceStates)
		{
			Integer vsId = getViewSetupIdFromBDVSource( state.getSpimSource() );
			if (vsId == null)
				continue;
			
			
			if (selected.contains( new ViewId( currentTimepoint, vsId ) ))
			{
				((TransformedSource< ? >) state.getSpimSource()).setFixedTransform( theTransform );
			}
			
		}
		
		
		bdvPopup.getBDV().getViewer().requestRepaint();
		
	}
	
	/**
	 * try to unwrap source to get the view setup id of wrapped and transformed SpimData view
	 * @param source - BigDataViewer source
	 * @return - view setup id if we can unwrap, null else
	 */
	public static Integer getViewSetupIdFromBDVSource(Source<?> source)
	{
		if (TransformedSource.class.isInstance( source ))
		{
			Source< ? > wrappedSource = ((TransformedSource< ? >) source).getWrappedSource();
			if (SpimSource.class.isInstance( wrappedSource ))
			{
				return ( (SpimSource<?> ) wrappedSource).getSetupId();
			}
			else
				return null;
		}
		else
			return null;
	}
	
	@Override
	public void selectedViewDescriptions(List< List< BasicViewDescription< ? > > > viewDescriptions)
	{
		// @pietzscht
		// the entire code here has nothing to do with changing colors (I uncommented everything and it still changes colors)
		final HashSet<ViewId> selectedNew = new HashSet<>();
		viewDescriptions.forEach( vdl -> selectedNew.addAll( vdl ) );
		
		// selection is the same as before, do nothing
		if (selectedNew.containsAll( selected ) && selected.containsAll( selectedNew ))
			return;
		
		// query user to apply if model is not identity
		if ( !TransformTools.allAlmostEqual( theTransform.getRowPackedCopy(), identity.getRowPackedCopy(), eps ) )
			applyTranslation();
		
		// reset and repaint Bdv if necessary
		if ( bdvPopup.bdvRunning() )
		{
			//BDVTools.resetBDVManualTransformations( bdvPopup.getBDV() );
			//bdvPopup.getBDV().getViewer().requestRepaint();
		}
		
		// update selection
		selected.clear();
		selected.addAll( selectedNew );
		
		bb = new BoundingBoxMaximal( selected, data ).estimate( "" );
		
		theTransform = new AffineTransform3D();
		resetSliders();
	}
	
	@Override
	public void updateContent(AbstractSpimData< ? > data)
	{
		if (SpimData2.class.isInstance( data ))
			this.data = (SpimData2) data;
		else
			this.data = null;

		this.selected.clear();
	}
	
	@Override
	public void save()
	{
		// query user to apply if model is not identity
		if (!TransformTools.allAlmostEqual( theTransform.getRowPackedCopy(), identity.getRowPackedCopy(), eps ))
			applyTranslation();
	}
	
	@Override
	public void quit()
	{
		// query user to apply if model is not identity
		if ( !TransformTools.allAlmostEqual( theTransform.getRowPackedCopy(), identity.getRowPackedCopy(), eps ) )
			applyTranslation();

		// reset and repaint Bdv if necessary
		if ( bdvPopup.bdvRunning() )
		{
			BDVTools.resetBDVManualTransformations( bdvPopup.getBDV() );
			bdvPopup.getBDV().getViewer().requestRepaint();
		}
		
		// close parent, also removing this from panels listeners
		parent.dispatchEvent(new WindowEvent(parent, WindowEvent.WINDOW_CLOSING));
	}
}
