/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.scm;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.IWizard;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.actions.NewProjectAction;

import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;

import org.apache.maven.model.Model;

import org.maven.ide.eclipse.MavenConsole;
import org.maven.ide.eclipse.MavenPlugin;
import org.maven.ide.eclipse.embedder.MavenModelManager;
import org.maven.ide.eclipse.project.LocalProjectScanner;
import org.maven.ide.eclipse.project.MavenProjectInfo;
import org.maven.ide.eclipse.project.ProjectImportConfiguration;
import org.maven.ide.eclipse.wizards.MavenImportWizard;
import org.maven.ide.eclipse.wizards.ProjectsImportWizard;


/**
 * Maven project checkout Job
 * 
 * @author Eugene Kuleshov
 */
public abstract class MavenProjectCheckoutJob extends WorkspaceJob {
  final MavenCheckoutOperation operation = new MavenCheckoutOperation();

  final ProjectImportConfiguration configuration;
  
  boolean checkoutAllProjects;

  Collection projects;

  public MavenProjectCheckoutJob(ProjectImportConfiguration importConfiguration, boolean checkoutAllProjects) {
    super("Checking out Maven projects");
    this.configuration = importConfiguration;
    this.checkoutAllProjects = checkoutAllProjects;

    addJobChangeListener(new CheckoutJobChangeListener());
  }
  
  public void setLocation(File location) {
    operation.setLocation(location);
  }
  
  protected abstract Collection getProjects(IProgressMonitor monitor) throws InterruptedException;
  
  
  // WorkspaceJob
  
  public IStatus runInWorkspace(IProgressMonitor monitor) throws CoreException {
    try {
      operation.setMavenProjects(getProjects(monitor));
      operation.run(monitor);

      LocalProjectScanner scanner = new LocalProjectScanner(operation.getLocations(), true);
      scanner.run(monitor);

      MavenPlugin plugin = MavenPlugin.getDefault();
      MavenModelManager modelManager = plugin.getMavenModelManager();

      boolean includeModules = configuration.getResolverConfiguration().shouldIncludeModules();
      this.projects = plugin.getProjectConfigurationManager().collectProjects(scanner.getProjects(), includeModules);

      if(checkoutAllProjects) {
        // check if there any project name conflicts 
        IWorkspaceRoot workspace = ResourcesPlugin.getWorkspace().getRoot();
        for(Iterator it = projects.iterator(); it.hasNext();) {
          MavenProjectInfo projectInfo = (MavenProjectInfo) it.next();
          Model model = projectInfo.getModel();
          if(model == null) {
            model = modelManager.readMavenModel(projectInfo.getPomFile());
            projectInfo.setModel(model);
          }

          String projectName = configuration.getProjectName(model);
          IProject project = workspace.getProject(projectName);
          if(project.exists()) {
            checkoutAllProjects = false;
            break;
          }
        }
      }

      return Status.OK_STATUS;

    } catch(InterruptedException ex) {
      return Status.CANCEL_STATUS;

    } catch(InvocationTargetException ex) {
      Throwable cause = ex.getTargetException() == null ? ex : ex.getTargetException();
      if(cause instanceof CoreException) {
        return ((CoreException) cause).getStatus();
      }
      return new Status(IStatus.ERROR, MavenPlugin.PLUGIN_ID, 0, cause.toString(), cause);
    }
  }

  /**
   * Checkout job listener
   */
  final class CheckoutJobChangeListener extends JobChangeAdapter {

    public void done(IJobChangeEvent event) {
      IStatus result = event.getResult();
      if(result.getSeverity() == IStatus.CANCEL) {
        return;
      } else if(!result.isOK()) {
        // XXX report errors
        return;
      }

      if(projects.isEmpty()) {
        MavenPlugin.getDefault().getConsole().logMessage("No Maven projects to import");
        
        final List locations = operation.getLocations();
        if(locations.size()==1) {
          final String location = (String) locations.get(0);
          
          DirectoryScanner projectScanner = new DirectoryScanner();
          projectScanner.setBasedir(location);
          projectScanner.setIncludes(new String[] {"**/.project"});
          projectScanner.scan();
          
          String[] projectFiles = projectScanner.getIncludedFiles();
          if(projectFiles!=null && projectFiles.length>0) {
            Display.getDefault().asyncExec(new Runnable() {
              public void run() {
                boolean res = MessageDialog.openConfirm(Display.getDefault().getActiveShell(), //
                    "Project Import", //
                    "No Maven projects found, but there is Eclipse projects configuration avaialble.\n" +
                    "Do you want to select and import Eclipse projects?");
                if(res) {
                  IWizard wizard = new ProjectsImportWizard(((String) locations.get(0)));
                  WizardDialog dialog = new WizardDialog(Display.getDefault().getActiveShell(), wizard);
                  dialog.open();
                } else {
                  cleanup(locations);
                }
              }
            });
            return;
          }
          
          Display.getDefault().syncExec(new Runnable() {
            public void run() {
              boolean res = MessageDialog.openConfirm(Display.getDefault().getActiveShell(), //
                  "Project Import", //
                  "No Maven projects found. Do you want to create project using new project wizard?\n"
                      + "Check out location will be copied into clipboard.");
              if(res) {
                Clipboard clipboard = new Clipboard(Display.getDefault());
                clipboard.setContents(new Object[] { location }, new Transfer[] { TextTransfer.getInstance() });
                
                NewProjectAction newProjectAction = new NewProjectAction(PlatformUI.getWorkbench().getActiveWorkbenchWindow());
                newProjectAction.run();
              } else {
                cleanup(locations);
              }
            }
          });
          return;
        }

        cleanup(locations);
      }
      
      configuration.setNeedsRename(true);
      
      if(checkoutAllProjects) {
        final MavenPlugin plugin = MavenPlugin.getDefault();
        WorkspaceJob job = new WorkspaceJob("Importing Maven projects") {
          public IStatus runInWorkspace(IProgressMonitor monitor) {
            Set projectSet = plugin.getProjectConfigurationManager().collectProjects(projects, //
                configuration.getResolverConfiguration().shouldIncludeModules());

            try {
              plugin.getProjectConfigurationManager().importProjects(projectSet, configuration, monitor);
            } catch(CoreException ex) {
              plugin.getConsole().logError("Projects imported with errors");
              return ex.getStatus();
            }

            return Status.OK_STATUS;
          }
        };
        job.setRule(plugin.getProjectConfigurationManager().getRule());
        job.schedule();

      } else {
        Display.getDefault().asyncExec(new Runnable() {
          public void run() {
            List locations = operation.getLocations();
            MavenImportWizard wizard = new MavenImportWizard(configuration, locations);
            WizardDialog dialog = new WizardDialog(Display.getDefault().getActiveShell(), wizard);
            int res = dialog.open();
            if(res == Window.CANCEL) {
              cleanup(locations);
            }
          }
        });
      }
    }

    protected void cleanup(List locations) {
      MavenConsole console = MavenPlugin.getDefault().getConsole();
      for(Iterator it = locations.iterator(); it.hasNext();) {
        String location = (String) it.next();
        try {
          FileUtils.deleteDirectory(location);
        } catch(IOException ex) {
          String msg = "Can't delete " + location;
          console.logError(msg + "; " + (ex.getMessage()==null ? ex.toString() : ex.getMessage()));
          MavenPlugin.log(msg, ex);
        }
      }
    }
    
  }
  
}
