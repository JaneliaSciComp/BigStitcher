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
package net.preibisch.stitcher.gui.overlay;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;
import bdv.viewer.OverlayRenderer;
import bdv.viewer.TransformListener;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.explorer.SelectedViewDescriptionListener;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseLinkInterface;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;
import net.preibisch.stitcher.algorithm.globalopt.TransformationTools;

public class DemoLinkOverlay implements OverlayRenderer, TransformListener< AffineTransform3D >, SelectedViewDescriptionListener< AbstractSpimData<?> >
{
	final private ArrayList< Pair< Group< ViewId >, Group< ViewId > > > lastFilteredResults, lastInconsistentResults;
	private PairwiseLinkInterface results;
	private AbstractSpimData< ? > spimData;
	private AffineTransform3D viewerTransform;
	public boolean isActive;
	private ArrayList<Pair<Group<ViewId>, Group<ViewId>>> activeLinks; //currently selected in the GUI

	final Stroke dashed = new BasicStroke( 1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{4}, 0 );
	final Stroke thin = new BasicStroke( 1 );
	final Stroke thick = new BasicStroke( 1.5f );

	public DemoLinkOverlay( PairwiseLinkInterface results, AbstractSpimData< ? > spimData )
	{
		this.results = results;
		this.spimData = spimData;
		this.lastFilteredResults = new ArrayList<>();
		this.lastInconsistentResults = new ArrayList<>();
		viewerTransform = new AffineTransform3D();
		isActive = false;
		activeLinks = new ArrayList<>();
	}

	public void setPairwiseLinkInterface( final PairwiseLinkInterface pli )
	{
		this.results = pli;
	}

	// called by FilteredStitchingResults
	public ArrayList< Pair< Group< ViewId >, Group< ViewId > > > getFilteredResults()
	{
		return lastFilteredResults;
	}

	// called by e.g. Global Optimization
	public ArrayList< Pair< Group< ViewId >, Group< ViewId > > > getInconsistentResults()
	{
		return lastInconsistentResults;
	}

	@Override
	public void transformChanged(AffineTransform3D transform)
	{
		this.viewerTransform = transform;
	}

	@Override
	public void drawOverlays(Graphics g)
	{
		// dont do anything if the overlay was set to inactive or we have no Tile selected (no links to display)
		if (!isActive || activeLinks.size() == 0)
			return;

		for ( Pair<Group<ViewId>, Group<ViewId>> p: activeLinks)
		{
			if ( spimData.getSequenceDescription().getMissingViews() != null )
			{
				p.getA().filterMissingViews( spimData.getSequenceDescription().getMissingViews().getMissingViews() );
				p.getB().filterMissingViews( spimData.getSequenceDescription().getMissingViews().getMissingViews() );
			}

			// local coordinates of views, without BDV transform 
			final double[] lPos1 = new double[ 3 ];
			final double[] lPos2 = new double[ 3 ];
			// global coordianates, after BDV transform
			final double[] gPos1 = new double[ 3 ];
			final double[] gPos2 = new double[ 3 ];
			
			BasicViewDescription<?> vdA = spimData.getSequenceDescription().getViewDescriptions().get( p.getA().iterator().next() );
			BasicViewDescription<?> vdB = spimData.getSequenceDescription().getViewDescriptions().get( p.getB().iterator().next() );
			ViewRegistration vrA = spimData.getViewRegistrations().getViewRegistration(  p.getA().iterator().next() );
			ViewRegistration vrB = spimData.getViewRegistrations().getViewRegistration(  p.getB().iterator().next() );

			long[] sizeA = new long[vdA.getViewSetup().getSize().numDimensions()];
			long[] sizeB = new long[vdB.getViewSetup().getSize().numDimensions()];
			spimData.getSequenceDescription().getViewDescriptions().get( p.getA().iterator().next() ).getViewSetup().getSize().dimensions( sizeA );
			spimData.getSequenceDescription().getViewDescriptions().get( p.getB().iterator().next() ).getViewSetup().getSize().dimensions( sizeB );
			
			// TODO: this uses the transform of the first view in the set, maybe do something better?
			AffineTransform3D vt1 = spimData.getViewRegistrations().getViewRegistration( p.getA().iterator().next() ).getModel();
			AffineTransform3D vt2 = spimData.getViewRegistrations().getViewRegistration( p.getB().iterator().next() ).getModel();
			
			boolean overlaps = SimpleBoundingBoxOverlap.overlaps( SimpleBoundingBoxOverlap.getBoundingBox(	vdA.getViewSetup(), vrA ), SimpleBoundingBoxOverlap.getBoundingBox( vdB.getViewSetup(), vrB ) );

			if (!overlaps)
				continue;

			final AffineTransform3D transform = new AffineTransform3D();
			transform.preConcatenate( viewerTransform );

			for( int i = 0; i < 3; i++)
			{
				// start from middle of view
				lPos1[i] += sizeA[i] / 2;
				lPos2[i] += sizeB[i] / 2;
			}

			vt1.apply( lPos1, lPos1 );
			vt2.apply( lPos2, lPos2 );
			
			transform.apply( lPos1, gPos1 );
			transform.apply( lPos2, gPos2 );

			Graphics2D g2d = null;

			if ( Graphics2D.class.isInstance( g ) )
				g2d = (Graphics2D) g;
			/*
			if ( lastFilteredResults.contains( p ) || lastFilteredResults.contains( TransformationTools.reversePair( p ) ) )
			{
				g.setColor( Color.ORANGE );
				if ( g2d != null ) g2d.setStroke( dashed );
			}
			else if ( lastInconsistentResults.contains( p ) || lastInconsistentResults.contains( TransformationTools.reversePair( p ) ) )
			{
				g.setColor( Color.RED );
				if ( g2d != null ) g2d.setStroke( dashed );
			}
			else if ( results.getPairwiseLinks().contains( p ) || results.getPairwiseLinks().contains( TransformationTools.reversePair( p ) ) )
			{
				g.setColor( Color.GREEN );
				if ( g2d != null ) g2d.setStroke( thick );
			}
			else
			{
				g.setColor( Color.GRAY );
				if ( g2d != null ) g2d.setStroke( dashed );
			}
			*/

			if ( overlapsWith( p, lastFilteredResults ) )
			{
				g.setColor( Color.ORANGE );
				if ( g2d != null ) g2d.setStroke( dashed );
			}
			else if ( overlapsWith( p, lastInconsistentResults ) )
			{
				g.setColor( Color.RED );
				if ( g2d != null ) g2d.setStroke( dashed );
			}
			else if ( overlapsWith( p, results.getPairwiseLinks() ) )
			{
				g.setColor( Color.GREEN );
				if ( g2d != null ) g2d.setStroke( thick );
			}
			else
			{
				g.setColor( Color.GRAY );
				if ( g2d != null ) g2d.setStroke( dashed );
			}

			g.drawLine((int) gPos1[0],(int) gPos1[1],(int) gPos2[0],(int) gPos2[1] );
		}
	}

	private static boolean overlapsWith( final Pair<Group<ViewId>, Group<ViewId>> p1, final Collection< Pair<Group<ViewId>, Group<ViewId>>> pairList )
	{
		for ( final Pair<Group<ViewId>, Group<ViewId>> p2 : pairList )
			if ( overlapsWith( p1, p2 ) )
				return true;

		return false;
	}

	private static boolean overlapsWith( final Pair<Group<ViewId>, Group<ViewId>> p1, final Pair<Group<ViewId>, Group<ViewId>> p2 )
	{
		boolean overlap1 = false;
		boolean overlap2 = false;

		for ( final ViewId v1A : p1.getA().getViews() )
			if ( p2.getA().contains( v1A ) )
				overlap1 = true;

		for ( final ViewId v1B : p1.getB().getViews() )
			if ( p2.getB().contains( v1B ) )
				overlap2 = true;

		if ( overlap1 && overlap2 )
			return true;

		overlap1 = false;
		overlap2 = false;

		for ( final ViewId v1A : p1.getA().getViews() )
			if ( p2.getB().contains( v1A ) )
				overlap2 = true;

		for ( final ViewId v1B : p1.getB().getViews() )
			if ( p2.getA().contains( v1B ) )
				overlap1 = true;

		if ( overlap1 && overlap2 )
			return true;
		else
			return false;
	}

	public void clearActiveLinks()
	{
		activeLinks.clear();
	}
	
	public void setActiveLinks(List<Pair<Group<ViewId>, Group<ViewId>>> vids)
	{
		activeLinks.clear();
		activeLinks.addAll( vids );
	}

	@Override
	public void setCanvasSize(int width, int height){}


	@Override
	public void selectedViewDescriptions( List< List< BasicViewDescription< ? > > > viewDescriptions )
	{
		List<Pair<Group<ViewId>, Group<ViewId>>> res = new ArrayList<>();
		for (int i = 0; i<viewDescriptions.size(); i++)
			for (int j = i+1; j<viewDescriptions.size(); j++)
			{
				Group<ViewId> groupA = new Group<>();
				groupA.getViews().addAll( viewDescriptions.get( i ) );
				Group<ViewId> groupB = new Group<>();
				groupB.getViews().addAll( viewDescriptions.get( j ) );
				res.add( new ValuePair< Group<ViewId>, Group<ViewId> >( groupA, groupB ) );
			}
		setActiveLinks( res );
	}


	@Override
	public void updateContent(AbstractSpimData< ? > data)
	{
		this.spimData = data;
	}


	@Override
	public void save()
	{
	}


	@Override
	public void quit()
	{
	}

}
