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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineTransform3D;
import bdv.viewer.OverlayRenderer;
import bdv.viewer.TransformListener;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseStitchingResult;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;



public class LinkOverlay implements OverlayRenderer, TransformListener< AffineTransform3D >
{
	private StitchingResults stitchingResults;
	private AbstractSpimData< ? > spimData;	
	private final AffineTransform3D viewerTransform;	
	public boolean isActive;
	private ArrayList<Pair<Group< ViewId>, Group<ViewId>>> activeLinks;
	private ValuePair<Group<ViewId>, Group< ViewId>> selectedLink;
	private Group<ViewId> reference;
	
	public void clearActiveLinks()
	{
		activeLinks.clear();
		this.reference = null;
	}
	
	public void setActiveLinks(List<Pair<Group<ViewId>, Group<ViewId>>> vids, Group<ViewId> reference)
	{
		activeLinks.clear();
		activeLinks.addAll( vids );
		this.reference = reference;
	}
	
	public void setSelectedLink(Pair<Group<ViewId>, Group<ViewId>> link)
	{
		if (link == null)
			selectedLink = null;
		else
			selectedLink = new ValuePair<>( link.getA(), link.getB() );
	}
	

	/** screen pixels [x,y,z] **/	
	private static Color getColor( final double corr, final double maxR, final double minR )
	{
		final double r = ( corr - minR ) / ( maxR - minR );
		
		final double red = r > 0.5 ? Math.max( 0, (1 - r) * 2 ): 1.0;
		final double green = r > 0.5 ? 1 : Math.max( 0, 2*r );

		return new Color( (float)red, (float)green, 0.0f, 1.0f);
	}

	public LinkOverlay( StitchingResults res, AbstractSpimData< ? > spimData)
	{
		this.stitchingResults = res;
		this.spimData = spimData;
		viewerTransform = new AffineTransform3D();
		isActive = false;
		activeLinks = new ArrayList<>();
		selectedLink = null;
	}

	@Override
	public void transformChanged( final AffineTransform3D transform )
	{
		viewerTransform.set( transform );
	}

	public static void drawViewOutlines( final Graphics2D g, final Dimensions dims, final AffineTransform3D transfrom, final Color color )
	{
		final int n = dims.numDimensions();

		final Queue< List< Boolean > > worklist = new LinkedList<>();
		// add 0,0,..
		final List< Boolean > origin = new ArrayList<>();
		for (int d = 0; d < n; d++)
			origin.add( false );
		worklist.add( origin );

		while ( worklist.size() > 0 )
		{
			final List< Boolean > vertex1 = worklist.poll();
			final List< List< Boolean > > neighbors = getHigherVertices( vertex1 );

			worklist.addAll( neighbors );

			for (final List<Boolean> vertex2 : neighbors)
			{
				final double[] v1Pos = new double[ n ];
				final double[] v2Pos = new double[ n ];

				for (int d = 0; d < n; d++)
				{
					// the outline goes from -0.5 to dimension(d) - 0.5 (compared to the actual range of (0, dim(d)-1))
					// this is because BDV (correctly) draws pixels with center at pixel location 
					v1Pos[d] = vertex1.get( d ) ? dims.dimension( d ) - 0.5 : -0.5;
					v2Pos[d] = vertex2.get( d ) ? dims.dimension( d ) - 0.5 : -0.5;
				}

				transfrom.apply( v1Pos, v1Pos );
				transfrom.apply( v2Pos, v2Pos );

				g.setColor( color );
				g.setStroke( new BasicStroke( 1.0f ) );
				g.drawLine((int) v1Pos[0],(int) v1Pos[1],(int) v2Pos[0],(int) v2Pos[1] );
			}
			
		}
		
	}
	
	/**
	 * take a vertex of a unit hypercube (coordinates represented by booleans)
	 * and generate all vertices reachable by *incrementing* in one dimension
	 * e.g. {@literal(0,0) -> ((0,1), (1,0))}
	 * @param from - the vertex to start from
	 * @return - the vertices reachable by one increment
	 */
	public static List< List< Boolean > > getHigherVertices(List< Boolean > from)
	{
		final ArrayList< List< Boolean > > higherVertices = new ArrayList<>();
		
		for (int d = 0; d < from.size(); d++)
		{
			if (from.get( d ))
				continue;
			
			final ArrayList< Boolean > movedInD = new ArrayList<>(from);
			movedInD.set( d, true );
			higherVertices.add( movedInD );
		}
		
		return higherVertices;		
	}
	
	@Override
	public void drawOverlays( final Graphics g )
	{
		// dont do anything if the overlay was set to inactive or we have no Tile selected (no links to display)
		if (!isActive || activeLinks.size() == 0)
			return;
		
		final Graphics2D graphics = ( Graphics2D ) g;
		
		
		double maxr = 0.0;
		double minr = Double.MAX_VALUE;
		for (PairwiseStitchingResult<ViewId> sr : stitchingResults.getPairwiseResults().values())
		{
			maxr = Math.max( maxr, sr.r() );
			minr = Math.min( minr, sr.r() );
		}
		
		//System.out.println( activeLinks );
		
		final Set< ViewId > outlinedViews = new HashSet<>();
		
		for (Pair< Group<ViewId>, Group<ViewId> > p : stitchingResults.getPairwiseResults().keySet())
		{
			if (activeLinks.size() > 0 && !(activeLinks.contains( p )))
				continue;
			
			// local coordinates of views, without BDV transform 
			final double[] lPos1 = new double[ 3 ];
			final double[] lPos2 = new double[ 3 ];
			// global coordianates, after BDV transform
			final double[] gPos1 = new double[ 3 ];
			final double[] gPos2 = new double[ 3 ];
			
			long[] sizeA = new long[spimData.getSequenceDescription().getViewDescriptions().get( p.getA().getViews().iterator().next() ).getViewSetup().getSize().numDimensions()];
			long[] sizeB = new long[spimData.getSequenceDescription().getViewDescriptions().get( p.getB().getViews().iterator().next() ).getViewSetup().getSize().numDimensions()];
			spimData.getSequenceDescription().getViewDescriptions().get( p.getA().getViews().iterator().next() ).getViewSetup().getSize().dimensions( sizeA );
			spimData.getSequenceDescription().getViewDescriptions().get( p.getB().getViews().iterator().next() ).getViewSetup().getSize().dimensions( sizeB );
			
			// TODO: this uses the transform of the first view in the set, maybe do something better?
			AffineTransform3D vt1 = spimData.getViewRegistrations().getViewRegistration( p.getA().getViews().iterator().next() ).getModel();
			AffineTransform3D vt2 = spimData.getViewRegistrations().getViewRegistration( p.getB().getViews().iterator().next() ).getModel();

			for(int i = 0; i < 3; i++)
			{
				// start from middle of view
				lPos1[i] += sizeA[i] / 2;
				lPos2[i] += sizeB[i] / 2;
			}

			vt1.apply( lPos1, lPos1 );
			vt2.apply( lPos2, lPos2 );
			
			if (!p.getA().equals( reference ))
				stitchingResults.getPairwiseResults().get( p ).getTransform().applyInverse( lPos2, lPos2 );
			if (!p.getB().equals( reference ))
				stitchingResults.getPairwiseResults().get( p ).getTransform().apply( lPos1, lPos1 );

			viewerTransform.apply( lPos1, gPos1 );
			viewerTransform.apply( lPos2, gPos2 );
			
			// if we have an active link, color it white, else red->yellow->green depending on the correlation
			if (p.equals( selectedLink ))
				graphics.setColor( Color.WHITE );
			else
				graphics.setColor( getColor( stitchingResults.getPairwiseResults().get( p ).r(), maxr, minr ) );
			
			graphics.setStroke( new BasicStroke( 2.0f ) );
			graphics.drawLine((int) gPos1[0],(int) gPos1[1],(int) gPos2[0],(int) gPos2[1] );
			
			
			// draw outlines for views in A
			for (final ViewId vid : p.getA().getViews())
			{
				if (!p.equals( selectedLink ) && outlinedViews.contains( vid ))
					continue;

				final boolean isReference = p.getA().equals( reference );
				
				final Dimensions dims = spimData.getSequenceDescription().getViewDescriptions().get( vid ).getViewSetup().getSize();
				final AffineTransform3D registration = spimData.getViewRegistrations().getViewRegistration( vid ).getModel();
				final AffineTransform3D finalTransform = 
						registration.copy()
						.preConcatenate( isReference ? new AffineTransform3D() : stitchingResults.getPairwiseResults().get( p ).getInverseTransform() )
						.preConcatenate( viewerTransform );

				drawViewOutlines( graphics, dims, finalTransform, p.equals( selectedLink ) ? Color.MAGENTA : Color.GRAY );
				outlinedViews.add( vid );
			}

			// draw outlines for views in B
			for ( final ViewId vid : p.getB().getViews() )
			{
				if ( !p.equals( selectedLink ) && outlinedViews.contains( vid ) )
					continue;

				final boolean isReference = p.getB().equals( reference );

				final Dimensions dims = spimData.getSequenceDescription().getViewDescriptions().get( vid )
						.getViewSetup().getSize();
				final AffineTransform3D registration = spimData.getViewRegistrations().getViewRegistration( vid )
						.getModel();
				final AffineTransform3D finalTransform = 
						registration.copy()
						.preConcatenate( isReference ? new AffineTransform3D() : stitchingResults.getPairwiseResults().get( p ).getTransform() )
						.preConcatenate( viewerTransform );

				drawViewOutlines( graphics, dims, finalTransform, p.equals( selectedLink ) ? Color.GREEN : Color.GRAY );
				outlinedViews.add( vid );
			}
			
			
		}
	}

	@Override
	public void setCanvasSize( final int width, final int height )
	{}
	
	public static void main(String[] args)
	{
		double maxR = 2;
		double minR = 1;
		
		for (double corr = 2.0; corr >= 1.0; corr -= 0.1) 
		{
			final double r = ( corr - minR ) / ( maxR - minR );
					
			final double red = r > 0.5 ? Math.max( 0, (1 - r) * 2 ): 1.0;
			final double green = r > 0.5 ? 1 : Math.max( 0, 2*r );
			System.out.println( red + " " + green );
		}
		
		List< Boolean > start = Arrays.asList( false, false, false );
		List< List< Boolean > > moves = getHigherVertices( start );
		moves.forEach( System.out::println );
	}
}
