/*
 * JBoss, Home of Professional Open Source.
 * 
 * Copyright 2007, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.security.negotiation.spnego;

import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.acl.Group;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.Oid;
import org.jboss.security.SimpleGroup;
import org.jboss.security.auth.spi.AbstractServerLoginModule;
import org.jboss.security.negotiation.NegotiationMessage;
import org.jboss.security.negotiation.common.NegotiationContext;
import org.jboss.security.negotiation.spnego.encoding.NegTokenInit;
import org.jboss.security.negotiation.spnego.encoding.NegTokenTarg;
import org.jboss.security.negotiation.spnego.encoding.SPNEGOMessage;

/**
 * Login module to work in conjunction with SPNEGOAuthenticator to handle the 
 * authentication requirements. 
 * 
 * @author darran.lofthouse@jboss.com
 * @version $Revision$
 */
public class SPNEGOLoginModule extends AbstractServerLoginModule
{

   private static final String SPNEGO = "SPNEGO";

   private static final String REMOVE_REALM_FROM_PRINCIPAL = "removeRealmFromPrincipal";

   private static final Oid kerberos;

   // TODO - Pick a name for a default domain?
   private String serverSecurityDomain;

   private boolean removeRealmFromPrincipal;

   private LoginContext serverLoginContext = null;

   private Principal identity = null;

   static
   {
      try
      {
         kerberos = new Oid("1.2.840.113554.1.2.2");
      }
      catch (GSSException e)
      {
         throw new RuntimeException("Unable to initialise Oid", e);
      }
   }

   @Override
   public void initialize(final Subject subject, final CallbackHandler callbackHandler, final Map sharedState,
         final Map options)
   {
      super.initialize(subject, callbackHandler, sharedState, options);
      String temp;
      // Which security domain to authenticate the server.
      serverSecurityDomain = (String) options.get("serverSecurityDomain");
      log.debug("serverSecurityDomain=" + serverSecurityDomain);
      temp = (String) options.get(REMOVE_REALM_FROM_PRINCIPAL);
      removeRealmFromPrincipal = Boolean.valueOf(temp);
      if (removeRealmFromPrincipal == false && principalClassName == null)
      {
         principalClassName = KerberosPrincipal.class.getName();
      }
   }

   @Override
   public boolean login() throws LoginException
   {
      if (super.login() == true)
      {
         log.debug("super.login()==true");
         return true;
      }

      super.loginOk = false;

      NegotiationContext negotiationContext = NegotiationContext.getCurrentNegotiationContext();
      NegotiationMessage requestMessage = negotiationContext.getRequestMessage();
      if (requestMessage instanceof SPNEGOMessage == false)
      {
         String message = "Unsupported negotiation mechanism '" + requestMessage.getMessageType() + "'.";
         log.warn(message);
         throw new LoginException(message);
      }

      try
      {
         Subject server = getServerSubject();
         AcceptSecContext action = new AcceptSecContext(negotiationContext);
         Object result = Subject.doAs(server, action);

         log.trace("Result - " + result);

         if (result instanceof Boolean)
         {
            if (Boolean.TRUE.equals(result))
            {
               super.loginOk = true;
               if (getUseFirstPass() == true)
               {
                  String userName = identity.getName();
                  log.debug("Storing username '" + userName + "' and empty password");
                  // Add the username and a null password to the shared state map
                  sharedState.put("javax.security.auth.login.name", identity);
                  sharedState.put("javax.security.auth.login.password", "");
               }
            }
         }
         else if (result instanceof Exception)
         {
            Exception e = (Exception) result;
            log.error("Unable to authenticate", e);
            throw new LoginException("Unable to authenticate - " + e.getMessage());
         }

      }
      finally
      {
         if (serverLoginContext != null)
         {
            // TODO - We may not actually want to logout as if we use cache this may clear it,
            serverLoginContext.logout();
         }
      }

      log.trace("super.loginOk " + super.loginOk);
      if (super.loginOk == true)
      {
         return true;
      }
      else
      {
         throw new LoginException("Continuation Required.");
      }

   }

   @Override
   protected Principal getIdentity()
   {
      return identity;
   }

   @Override
   protected Principal createIdentity(final String username) throws Exception
   {
      if (removeRealmFromPrincipal)
      {
         return super.createIdentity(username.substring(0, username.indexOf("@")));
      }
      else
      {
         return super.createIdentity(username);
      }

   }

   @Override
   protected Group[] getRoleSets() throws LoginException
   {

      Group roles = new SimpleGroup("Roles");
      Group callerPrincipal = new SimpleGroup("CallerPrincipal");
      Group[] groups =
      {roles, callerPrincipal};
      callerPrincipal.addMember(getIdentity());
      return groups;
   }

   protected Subject getServerSubject() throws LoginException
   {
      LoginContext lc = new LoginContext(serverSecurityDomain);
      lc.login();
      // Cache so we can log out.
      serverLoginContext = lc;

      Subject serverSubject = serverLoginContext.getSubject();
      log.debug("Subject = " + serverSubject);
      log.debug("Logged in '" + serverSecurityDomain + "' LoginContext");

      return serverSubject;
   }

   private class AcceptSecContext implements PrivilegedAction
   {

      private final NegotiationContext negotiationContext;

      public AcceptSecContext(final NegotiationContext negotiationContext)
      {
         this.negotiationContext = negotiationContext;
      }

      public Object run()
      {
         try
         {
            // The message type will have already been checked before this point so we know it is
            // a SPNEGO message.
            NegotiationMessage requestMessage = negotiationContext.getRequestMessage();

            // TODO - Ensure no way to fall through with gssToken still null.
            byte[] gssToken = null;
            if (requestMessage instanceof NegTokenInit)
            {
               NegTokenInit negTokenInit = (NegTokenInit) requestMessage;
               List<Oid> mechList = negTokenInit.getMechTypes();

               if (mechList.get(0).equals(kerberos))
               {
                  gssToken = negTokenInit.getMechToken();
               }
               else
               {
                  boolean kerberosSupported = false;

                  Iterator<Oid> it = mechList.iterator();
                  while (it.hasNext() && kerberosSupported == false)
                  {
                     kerberosSupported = it.next().equals(kerberos);
                  }

                  NegTokenTarg negTokenTarg = new NegTokenTarg();

                  if (kerberosSupported)
                  {
                     negTokenTarg.setNegResult(NegTokenTarg.ACCEPT_INCOMPLETE);
                     negTokenTarg.setSupportedMech(kerberos);
                  }
                  else
                  {
                     negTokenTarg.setNegResult(NegTokenTarg.REJECTED);
                  }
                  negotiationContext.setResponseMessage(negTokenTarg);

                  return Boolean.FALSE;
               }

            }
            else if (requestMessage instanceof NegTokenTarg)
            {
               NegTokenTarg negTokenTarg = (NegTokenTarg) requestMessage;

               gssToken = negTokenTarg.getResponseToken();
            }

            Object schemeContext = negotiationContext.getSchemeContext();
            if (schemeContext != null && schemeContext instanceof GSSContext == false)
            {
               throw new IllegalStateException("The schemeContext is not a GSSContext");
            }

            GSSContext gssContext = (GSSContext) schemeContext;
            if (gssContext == null)
            {
               log.debug("Creating new GSSContext.");
               GSSManager manager = GSSManager.getInstance();
               gssContext = manager.createContext((GSSCredential) null);

               negotiationContext.setSchemeContext(gssContext);
            }

            if (gssContext.isEstablished())
            {
               log.warn("Authentication was performed despite already being authenticated!");

               // TODO - Refactor to only do this once.
               identity = new KerberosPrincipal(gssContext.getSrcName().toString());

               log.debug("context.getCredDelegState() = " + gssContext.getCredDelegState());
               log.debug("context.getMutualAuthState() = " + gssContext.getMutualAuthState());
               log.debug("context.getSrcName() = " + gssContext.getSrcName().toString());

               negotiationContext.setAuthenticationMethod(SPNEGO);
               negotiationContext.setAuthenticated(true);

               return Boolean.TRUE;
            }

            byte[] respToken = gssContext.acceptSecContext(gssToken, 0, gssToken.length);

            if (respToken != null)
            {
               NegTokenTarg negTokenTarg = new NegTokenTarg();
               negTokenTarg.setResponseToken(respToken);

               negotiationContext.setResponseMessage(negTokenTarg);
            }

            if (gssContext.isEstablished() == false)
            {
               return Boolean.FALSE;
            }
            else
            {
               identity = createIdentity(gssContext.getSrcName().toString());

               log.debug("context.getCredDelegState() = " + gssContext.getCredDelegState());
               log.debug("context.getMutualAuthState() = " + gssContext.getMutualAuthState());
               log.debug("context.getSrcName() = " + gssContext.getSrcName().toString());

               // TODO - Get these two in synch - maybe isAuthenticated based on an authentication method been set?
               negotiationContext.setAuthenticationMethod(SPNEGO);
               negotiationContext.setAuthenticated(true);
               return Boolean.TRUE;
            }

         }
         catch (Exception e)
         {
            return e;
         }

      }
   }
}
